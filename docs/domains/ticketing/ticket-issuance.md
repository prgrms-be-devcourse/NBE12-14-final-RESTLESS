> 현재 매핑 테스트 구성은 [문서 색인의 테스트 목록](README.md#매핑-테스트-구성)을 따른다. 아래 단계별 실행 건수는 최초 구현 시점의 이력이다.

# 티켓번호와 중복 발급 방지

- 티켓번호: DECIDED (2026-11-12, 담당자 합의).
- 중복 발급 방지 절차와 추가 제약: DECIDED (2026-11-12, 담당자 합의).
- 구현 상태: 기존 복합 UNIQUE·UUID v4 매핑을 사용해 TicketPaymentService.onApproved의 예약 확정·좌석별 발급·중복 방지를 구현했다. PaymentResultHandler 등록과 실제 결제 API 연결은 아직 다음 단계다.

## 1. 티켓번호

서버가 티켓 발급 시 UUID v4를 생성하여 tickets.ticket_no에 표준 소문자 하이픈 포함 문자열(36자)로 저장한다.
예: 8b4e35ae-179c-4bb8-97bd-4fc2c6d98bd3.
기존 VARCHAR(50)에 저장할 수 있으며 UNIQUE(ticket_no)를 유지한다.
내부 PK인 id는 ADR-0002에 따라 BIGINT AUTO_INCREMENT를 유지한다.

- 예매 확정 트랜잭션에서 좌석별 티켓 하나를 발급한다. 2좌석이면 티켓과 번호도 각각 2개다.
- 정상 발급된 번호는 바꾸지 않는다. 중복 결과 수신 시 기존 티켓을 반환한다.
- 환불 후에도 취소 티켓과 번호를 보존하고 재사용하지 않는다. 다른 예약으로 재판매하면 새 번호를 발급한다.
- ticket_no 자체는 조회 권한이나 입장 권한을 부여하는 비밀값으로 취급하지 않는다. 본인 조회 권한을 검증한다.
- QR 입장 토큰과 입장 사용 처리 정책은 별도 설계 대상이다.

## 2. 중복 발급 방지

번호의 유일성과 발급 대상의 유일성은 서로 다른 조건이다.
UUID를 매번 새로 만들면 같은 예약·좌석에 서로 다른 번호의 티켓이 두 장 생길 수 있다.
다음 제약으로 동일 예약·좌석의 중복 발급을 차단한다.

```sql
ALTER TABLE tickets
    ADD CONSTRAINT uk_tickets_reservation_seat
    UNIQUE (reservation_id, performance_seat_id);
```

기존 UNIQUE(ticket_no)는 유지한다. 추가 제약은 `V20261112_1020__ticketing_enforce_integrity_and_hold_request_keys.sql`에 정의한다.
dev에 병합된 기존 파일은 유지하며 공통 결제 V20261112_1000 다음에 통합 V20261112_1020으로 적용한다.
기존 중복 데이터가 있으면 제약 추가가 실패한다. 자동 삭제하지 않고 원인을 확인하여 정리한다.
적용 전 다음 조회로 중복을 확인한다.

```sql
SELECT reservation_id, performance_seat_id, COUNT(*) AS ticket_count
FROM tickets
GROUP BY reservation_id, performance_seat_id
HAVING COUNT(*) > 1;
```
취소 티켓도 포함하여 같은 예약·좌석에는 영구적으로 최대 한 장만 존재한다.
재구매는 새 예약으로 처리하므로 환불 후 다른 예약에서 같은 좌석을 구매하는 것은 허용한다.
이 제약만으로 서로 다른 예약의 동일 좌석 중복 판매를 막지는 못한다. 그 조건은 [좌석 소유권 설계](seat-ownership.md)가 담당한다.

## 3. 확정 처리 순서

1. 좌석 소유권 설계의 순서대로 구매 관리 행 → 예약 행 → 좌석 행을 잠근다.
2. 결제 결과가 해당 주문·예약의 결과인지 검증한다. 이미 확정된 동일 결과라면 발급 티켓을 조회하여 반환하고 상태·카운터를 다시 변경하지 않는다.
3. 취소된 예약 등 확정 이후 상태에 도착한 과거 결과도 재발급하거나 상태를 되돌리지 않는다. 기존 결과와 현재 상태를 반환한다. 허용되지 않는 충돌 결과는 오류·복구 대상으로 분류한다.
4. 최초 확정이면 현재 좌석 소유자와 확정 가능한 예약 상태를 확인하고, 각 좌석의 티켓번호를 생성하여 INSERT 한다.
5. 예약 확정, 선점 CONFIRMED, 좌석 SOLD, 구매 카운터 변경, 티켓 발급과 주문 PAID를 동일 DB 트랜잭션으로 커밋한다. 내부 TicketPaymentService는 MANDATORY로 참여하며, 공통 결제의 동기 Handler 연결 후 결제 APPROVED 반영과도 같은 트랜잭션이 된다. 경계는 [결제 트랜잭션 설계](transactions.md)를 따른다.

동시 결과 처리는 같은 예약의 잠금으로 직렬화되고, UNIQUE는 누락된 처리 경로에서도 중복 INSERT를 거절하는 마지막 방어선이다.
중간 실패 시 전체 롤백하므로 일부 좌석만 발급된 상태를 커밋하지 않는다.
핵심 상태 변경을 AFTER_COMMIT 이벤트 리스너에 맡기지 않는다.

UNIQUE 충돌을 잡고 실패한 트랜잭션에서 그대로 계속 처리하지 않는다.
전체 롤백 후 새 트랜잭션에서 기존 확정 결과를 재검증한다.
예약·좌석 중복이면 기존 결과와 일관성이 확인될 때만 반환한다.
UUID 번호 자체의 충돌은 발급 대상 중복과 구분하여 새 번호로 전체 트랜잭션을 제한적으로 재시도한다.

## 4. 구현 검증 기준

아래는 계획이며 실행 결과가 아니다.

- 같은 성공 결과의 반복·동시 처리: 좌석당 티켓 한 장, ticket_no 불변, 카운터 한 번만 변경.
- 두 좌석 예약: 서로 다른 UUID 티켓 두 장 발급.
- 두 번째 티켓 INSERT 전후 실패: 전체 확정 트랜잭션 롤백 후 재처리로 정상 발급.
- 확정 커밋 후 응답 유실: 재요청 시 기존 번호 반환.
- 취소 후 과거 성공 결과 도착: 재발급·상태 복구 없음.
- 환불 후 새 예약의 동일 좌석 구매: 새 번호 발급, 기존 취소 이력 유지.
- 동일 예약·좌석 중복 INSERT는 추가 UNIQUE로 거절. UUID 충돌은 별도로 거절.

## 구현 현황: 발급 티켓 매핑 (최초 매핑 시점 기록)

- Ticket을 tickets에 매핑했다. 예약·회차·판매 좌석은 Long 스칼라 참조, 금액은 long, 발급·사용·취소 시각은 UTC Instant다.
- Ticket.issue는 내부 BIGINT ID와 별도로 UUID.randomUUID().toString()의 소문자 하이픈 포함 v4 번호를 생성한다. ticketNo/issuedAt은 JPA에서 updatable=false이며 번호를 변경하는 setter는 제공하지 않는다.
- usedAt/cancelledAt은 최초 발급 시 NULL이다. status는 기존 VARCHAR(20) 문자열로 매핑하고 호출자가 전달한다. ISSUED/USED/CANCELLED는 테스트 예시이며 허용 상태값 집합·입장/환불 정책은 아직 별도 정의 대상이다.
- 기존 UNIQUE(ticket_no), UNIQUE(reservation_id, performance_seat_id), 회차 일치 복합 FK와 금액 CHECK를 사용한다. 새 마이그레이션은 없다.
- TicketMappingTest는 MySQL Testcontainers/Flyway/ddl-auto=validate로 영속성 컨텍스트를 비운 뒤 저장 결과를 조회한다.
- UUID 버전·형식·번호 보존, 다중 좌석의 서로 다른 번호, 동일 발급 대상 중복·의도적 번호 충돌, 예약/판매 좌석의 회차 불일치·음수 금액 거절을 검증한다.
- 사용·취소 시각 및 새 예약 발급 시 과거 취소 티켓/번호 보존은 SQL fixture로 준비해 읽기와 제약을 검증한다. 실제 결제·환불·입장·재판매 로직을 구현한 것은 아니다.
- 이 엔티티만 생성해도 예약 확정·좌석 SOLD·카운터 변경이 일어나지는 않는다. 후속 결제 확정 트랜잭션에서 함께 처리해야 한다.
- ./gradlew build 실행으로 발급 티켓 매핑 테스트 10개를 포함해 전체 210개 테스트가 통과했다.

## 승인 성공 내부 서비스와 발급 상태 (2026-11-13)

발급 직후 티켓 상태는 사용자 합의로 ISSUED를 사용한다 (DECIDED). 실제 발급은 상태 인수를 받지 않는 Ticket.issue(..., now)로 생성한다. 기존 상태 인수 factory는 매핑 fixture 호환을 위해 유지하며 입장 사용·취소의 상태 전이는 이번에 확정하지 않는다.

- 공통 ApprovedPayment의 paymentId/approvalStartedAt이 예약이 받아들인 시도와 같은지 확인한다. 금액은 연결 공통 주문 총액과 비교하고 선점 이력 합계도 검증한다.
- 기한은 승인 시작 수락 때 판정했다. 현재 시각이나 PG approvedAt이 결제 기한 뒤여도 유효한 같은 시도의 성공이면 PAYMENT_PENDING/RECONCILING에서 확정한다.
- 구매 관리 → 예약 → 좌석 ID 오름차순 잠금 뒤 모든 HELD/현재 포인터를 확인하고, 선점 CONFIRMED·좌석 SOLD·예약 CONFIRMED·payment_result_pending=false·reserved-N/confirmed+N·좌석별 ISSUED 티켓·주문 PAID를 같은 트랜잭션에서 처리한다.
- 가격은 SeatHold.priceAmount, 발급 시각은 DB UTC 현재 시각이다. 기존 선점 포인터·최초 결제 기한·수락 시도 ID/시각은 보존한다.
- 같은 성공의 반복·동시 전달은 기존 주문 PAID·티켓 수/좌석/가격·SOLD 포인터·확정 수량의 일관성을 확인하고 ACCEPT한다. 새 번호를 생성하거나 수량을 다시 이동하지 않는다. 불일치는 기술 오류로 남기며 티켓을 임의 재생성하지 않는다.
- 다른 시도·승인 금액 불일치·소유권 상실·종료 상태는 REJECT한다. 모든 좌석을 먼저 검증하므로 일부 좌석만 변경한 채 거절하지 않는다. 이후 공통 보상 취소 연결은 다음 단계다.
- 티켓 INSERT나 후속 확정 중 기술 오류는 예외로 전체 롤백한다. UNIQUE 오류를 삼키고 같은 트랜잭션에서 계속하지 않는다. 같은 승인 결과의 재처리는 새 트랜잭션에서 수행하며 번호도 새로 생성한다.

이번 단계는 새 마이그레이션이나 공통 API 변경이 없다. 내부 성공 처리만 구현했으며 Handler 등록·실제 PG 호출·실패/불명확/만료/보상 취소·정상 환불·입장 처리는 아직 다음 단계다.

필수 테스트는 기존 checkout fixture에 ApprovalConfirmation 중첩 테스트 4개 메서드/10개 사례로 추가했다. 늦은 성공 2개 상태, 동시·반복 성공, 다른 시도/시각·금액·좌석 소유권·반환 예약 5개 거절, 두 번째 INSERT/전체 flush 후 오류 2개와 재처리를 검증한다. 기존 UUID 형식·DB UNIQUE·FK·checkout 가격·승인 시작 경계 테스트를 복제하지 않았다.
집중 명령 `./gradlew test --tests 'com.restless.ticketing.application.TicketCheckoutServiceTest*' --tests 'com.restless.ticketing.entity.TicketMappingTest'`: 39개 통과, 실패·오류·건너뜀 0개.
