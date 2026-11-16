> 현재 매핑 테스트 구성은 [문서 색인의 테스트 목록](README.md#매핑-테스트-구성)을 따른다. 아래 단계별 실행 건수는 최초 구현 시점의 이력이다.

# MySQL 좌석 소유권과 동시성

- 상태: DECIDED (2026-11-12, 담당자 합의)
- 범위: 회차별 좌석의 선점·반환·판매 소유권. 결제 기한은 [트랜잭션 설계](transactions.md)를 따른다. 불명확 결과의 자동 복구 상세는 별도 합의 대상이다.
- 기준: MySQL 8 / InnoDB, ADR-0002.
- 구현 상태: 설계 합의. 아래 추가 제약과 처리 절차가 현재 코드에 구현됐다는 의미는 아니다.

현재 선점 구현: POST /api/ticket-reservations에서 사용자·회차 구매 관리 행과 예약을 잠근 뒤 판매 좌석을 ID 오름차순으로 단건 잠근다.
예매 기간·배치의 판매 가능 여부·AVAILABLE/NULL 소유권·활성 예약·구매 한도를 검사하고,
예약·선점 이력·HELD/현재 선점 포인터·reserved_count를 한 트랜잭션에 반영한다.
DB UTC 시각 기준 선점 기한은 5분이며 재요청으로 연장하지 않는다. 결제 전 HELD 만료 반환도 같은 잠금 순서를 따른다.
공연·회차의 관리 status 값 집합은 데이터 사전상 미확정이므로 새 허용 상태를 임의로 정의하지 않으며, 관리 상태별 예매 차단은 후속 정책 대상이다.
결제 전 선점 취소 DELETE를 구현했다. @LoginMember의 회원 ID로 본인 예약인지 확인하고 구매 관리 행 → 예약 → 좌석 ID 오름차순 잠금 아래 전체 반환한다.
취소와 만료는 같은 좌석·이력·수량 반환 코드를 사용한다. 취소는 만료 전에도 HELD → RELEASED로 전이하며, 이미 RELEASED이면 다시 변경하지 않는다.
승인 성공 내부 서비스에서 HELD→SOLD 확정과 현재 선점 포인터 보존을 구현했다. 공통 Handler 등록·결제 이후 취소·환불의 소유권 전이는 다음 단계다. TicketReservationServiceTest에 선점 경쟁·동일 키·구매 한도·결제 전 만료와 결제 상태 보호 검증을 작성했으며 아직 실행하지 않았다. TicketReservationServiceTest에 본인·타인 취소, 중복 취소의 수량 보존, 결제 진행·결과 확인 중 취소 거절 테스트를 작성했으며 아직 실행하지 않았다.

## 1. 소유권의 기준

회차별 좌석 행 `performance_seats`가 현재 소유권의 기준이다. `seat_holds`는 이력이다.
`seat_holds.status = HELD` 검색 결과만으로 현재 소유자를 판단하지 않는다.

| 좌석 상태 | current_hold_id | 의미 |
|---|---|---|
| AVAILABLE | NULL | 신규 선점 가능 |
| HELD | 필수 | 선점·결제 진행·결과 확인 중 |
| SOLD | 필수 | 해당 선점으로 예매 확정 |

SOLD에서도 포인터를 유지한다. 환불 확정 시 좌석을 AVAILABLE로 바꾸고 포인터를 비운다.
선점·예매 이력은 삭제하지 않는다. 환불 후에도 과거 CONFIRMED 선점 이력을 보존한다.

불변식:

- 한 회차의 동일 물리 좌석 행은 하나다.
- 좌석의 현재 소유자는 최대 하나이며, 포인터는 반드시 그 좌석의 선점을 가리킨다.
- 모든 소유권 변경은 좌석 행의 배타적 잠금 아래 현재 상태와 소유자를 재검증한다.
- 예약·선점·좌석·구매 카운터는 한 트랜잭션에서 변경한다. 판매 확정 시 발급 티켓도 함께 반영한다.

구매 한도·활성 예약의 상세 검증과 상태별 카운터 변경은 [구매 제한 설계](purchase-limit.md)를 따른다.

## 2. 선점 트랜잭션

1. 사용자·회차별 구매 관리 행을 확보하고 잠근다. 최초 행 생성 경쟁은 UNIQUE(user_id, performance_id)로 처리한다. 충돌 시 실패한 트랜잭션을 정리하고 새 트랜잭션에서 기존 행을 잠근다.
2. 구매 한도와 사용자·회차의 활성 예약을 검증한다. 기존 예약을 변경해야 하면 해당 예약 행을 잠근다.
3. 요청 좌석을 ID 오름차순으로 SELECT ... FOR UPDATE 한다. 다중 좌석은 동일 순서로 잠금이 획득되도록 구현한다.
4. 모든 좌석의 회차·판매 가능 여부·AVAILABLE 상태를 재검증한다.
5. 예약과 좌석별 선점 이력을 생성하고, 좌석을 HELD로 변경하며 current_hold_id를 연결한다.
6. reserved_count를 요청 좌석 수만큼 증가시키고 커밋한다.

하나라도 실패하면 전체 롤백한다. 대기하던 요청은 잠금 획득 후 최신 상태를 검사한다.
DB 잠금은 이 짧은 트랜잭션 동안만 유지한다. 실제 5분 점유는 상태와 서버 기준 만료 시각으로 유지한다.

## 3. 공통 잠금 순서와 재시도

모든 소유권 변경 경로는 구매 관리 행 → 예약 행 → 좌석 행(ID 오름차순)의 순서를 따른다.
신규 예약은 아직 행이 없으므로 구매 관리 행 잠금으로 생성 경쟁을 직렬화한다.
예약·좌석 ID를 사전 조회했더라도 잠금 획득 뒤 관계와 상태를 다시 검증한다.
PG 호출 중에는 DB 트랜잭션을 유지하지 않는다. 주문·결제 모듈까지 포함한 전체 잠금 순서는 후속 결제 트랜잭션 설계에서 정한다.

만료 정리는 예약 한 건과 그 좌석 전체를 단위로 수행한다. 다른 사용자의 만료 예약을 새 선점 요청에서 발견하면,
새 요청의 트랜잭션과 잠금을 먼저 종료한다. 기존 소유자의 만료 정리를 별도 트랜잭션으로 수행한 뒤 신규 선점을 처음부터 재시도한다.
정리와 재시도 사이의 선점 성공은 보장하지 않는다. 재시도에서도 가용성을 검사한다.

교착·잠금 시간 초과 시 전체 트랜잭션을 롤백한다. 재시도는 횟수를 제한하고 동일 멱등성 키를 사용한다.
구체적인 횟수·대기 시간·HTTP 매핑은 API/오류 계약에서 정한다.

## 4. 반환과 판매 확정

만료·취소·환불·판매 확정에서도 공통 순서로 잠근 뒤 current_hold_id가 처리 대상 선점 ID와 같은지 확인한다.
다르면 좌석을 변경하지 않는다. 오래된 작업이 새 소유자의 좌석을 반환하거나 판매 처리해서는 안 된다.
포인터가 같더라도 예약 상태와 해당 전이의 조건을 검증한다. 반복 처리로 카운터를 두 번 변경하지 않는다.

- HELD 예약의 유효 기한 종료: 선점 RELEASED, 좌석 AVAILABLE/NULL, reserved_count 감소.
- 결제 확정: 선점 CONFIRMED, 좌석 SOLD/기존 포인터 유지, reserved_count 감소 및 confirmed_count 증가.
- 환불 확정: 좌석 AVAILABLE/NULL, confirmed_count 감소, 예약·발급 티켓 취소 반영. 선점 CONFIRMED 이력 보존.
- PAYMENT_PENDING 또는 RECONCILING은 최초 선점 만료 시각만으로 반환하지 않는다. 승인 미시도·모든 시도 최종 실패의 결제 기한 반환과 진행 중 시도 보호는 [만료 설계](expiration.md)를 따른다.
- 결제·환불 결과 불명확 시 소유권을 유지한다. 결제 기한은 [트랜잭션 설계](transactions.md)를 따른다. PG 재확인 주기와 운영 전환은 별도 설계 대상이다.

## 5. DB 제약과 구현 차이

현재 마이그레이션에 존재:

- UNIQUE(performance_id, venue_seat_id): 동일 회차의 물리 좌석 중복 생성 방지.
- UNIQUE(user_id, performance_id): 구매 관리 행 중복 생성 방지.
- UNIQUE(current_hold_id): 동일 선점을 여러 좌석의 현재 소유자로 연결하는 오류 방지.
- 구매 카운터 음수 방지 CHECK.

V20261112_1020 마이그레이션에 정의한 추가 제약 (DB 적용 여부는 환경별 확인):

- status를 AVAILABLE/HELD/SOLD로 제한하고, AVAILABLE이면 포인터 NULL, HELD/SOLD이면 포인터 NOT NULL인 CHECK.
- seat_holds의 UNIQUE(performance_seat_id, id)와 performance_seats의 복합 FK
  (id, current_hold_id) → seat_holds(performance_seat_id, id): 포인터의 소유 좌석 일치 보장.
- 예약·선점·좌석·티켓의 회차 일치 복합 FK. 구체적인 컬럼은 [관계 정합성](reservation-seat-consistency.md)을 따른다.

삽입은 AVAILABLE/NULL 좌석 → 선점 이력 → HELD/포인터 연결 순서로 같은 트랜잭션에서 처리한다.
순환 FK는 테이블 생성 후 추가한다. 병합된 기존 마이그레이션은 수정하지 않고 새 마이그레이션을 작성한다.

현재 소유권 하나만을 인정하는 구조이므로 PostgreSQL의 WHERE status = 'HELD' 부분 UNIQUE 인덱스를 사용하지 않는다.
DB 제약만으로 모든 상태 전이가 보장되는 것은 아니다. 모든 쓰기 경로가 잠금·상태·소유자 검증 절차를 따라야 한다.

## 6. 구현 검증 기준

MySQL Testcontainers에서 다음을 검증한다. 아래는 계획이며 실행 결과가 아니다.

- 동일 좌석 동시 선점: 성공 1건, 나머지는 가용성 충돌 또는 잠금 실패. 현재 소유자 1명.
- 선점 트랜잭션 실패: 예약·선점·포인터·카운터 전체 롤백.
- 다중 좌석 중 하나 불가: 일부 선점 없이 전체 실패.
- 같은 사용자·회차의 서로 다른 좌석 동시 요청: 활성 예약 최대 1건, 구매 한도 준수.
- 구매 관리 행 최초 생성 경쟁: 관리 행 1건, 한도·활성 예약 제약 유지.
- 만료/취소 중복 실행: 좌석 반환과 카운터 감소는 한 번만 반영.
- 만료 정리와 신규 선점 경합: 오래된 작업이 새 소유권을 해제하지 않음.
- 결제 진행·결과 불명확 중 최초 선점 기한 경과: 좌석 유지.
- 판매 확정·환불 완료 반복 처리: 포인터·티켓·카운터 일관성 유지.
- 다른 좌석의 선점 포인터, 상태/NULL 불일치: 추가 DB 제약으로 거절.

## 구현 현황: 회차별 판매 좌석 매핑

- TicketSeat를 performance_seats에 매핑했다. performanceId/layoutId/venueSeatId/currentHoldId는 스칼라 ID 참조다.
- price는 BIGINT에 맞춰 long, status는 TicketSeatStatus(AVAILABLE/HELD/SOLD)를 VARCHAR로 매핑한다. grade는 기존 VARCHAR(20) 문자열로 유지한다.
- createAvailable은 AVAILABLE/current_hold_id=NULL인 신규 판매 좌석만 생성한다. 임의 상태 setter나 선점·판매·반환 메서드는 이번 단계에서 추가하지 않는다.
- TicketSeatMappingTest는 MySQL Testcontainers/Flyway/ddl-auto=validate로 회차별 가격·등급·상태 저장과 조회, BIGINT 가격·0원 보존, 동일 회차 중복 좌석 거절을 검증한다.
- 회차와 다른 배치, 배치에 없는 좌석, 음수 가격, 잘못된 상태/소유자 조합과 다른 좌석의 선점 포인터 거절을 검증한다.
- HELD/SOLD 및 currentHoldId 읽기 검증은 예약·선점 엔티티로 이력을 생성하고 SQL fixture로 좌석 상태·포인터를 준비한다. 실제 선점·결제 전이 구현을 뜻하지 않는다.
- 기존 Flyway·API·Repository는 변경하지 않는다. 조회 API·비관적 락·예약·선점 로직은 후속 구현이다.
- 이번 매핑 추가 후 ./gradlew build 실행 성공. TicketSeatMappingTest 11개를 포함해 전체 172개 테스트가 통과했다.

## 구현 현황: 좌석별 선점 이력 매핑

- SeatHold를 seat_holds에 매핑했다. 예약·회차·판매 좌석은 Long 스칼라 ID, 가격 스냅샷은 long, 만료·생성·해제 시각은 UTC Instant다.
- SeatHoldStatus는 HELD/CONFIRMED/RELEASED를 기존 VARCHAR(20)에 매핑한다. createHeld는 HELD/released_at=NULL로 생성한다.
- 만료·생성 시각과 가격은 호출자가 전달한다. 기한 계산·회차/가격 검증·좌석 잠금·카운터 반영·이력 전이는 후속 application 구현 범위다.
- 선점 엔티티 생성만으로 현재 소유권이 바뀌지 않는다. 실제 선점 서비스는 좌석 포인터 변경과 예약·카운터 변경을 같은 트랜잭션에서 수행해야 한다.
- SeatHoldMappingTest는 MySQL Testcontainers/Flyway/ddl-auto=validate로 다중 좌석 이력·가격 스냅샷·UTC 마이크로초·NULL 해제 시각을 저장하고 영속성 컨텍스트를 비워 조회한다.
- 기존 복합 FK로 예약·판매 좌석의 회차 불일치, CHECK로 음수 가격 거절을 검증한다. 후속 상태와 과거 이력 보존·현재 포인터 연결은 SQL fixture로 준비하여 매핑을 확인한다.
- TicketSeatMappingTest도 구현된 예약·선점 엔티티를 사용하도록 fixture를 갱신했다. 실제 선점·해제 알고리즘을 테스트한 것은 아니다.
- 기존 Flyway·공개 API·Repository는 변경하지 않는다.
- ./gradlew build 실행으로 선점 이력 매핑 테스트 8개를 포함해 전체 200개 테스트가 통과했다.

선점 이력의 내부 이름은 SeatHold/SeatHoldStatus/SeatHoldRepository로 통일하고 매핑 테스트는 SeatHoldMappingTest로 변경했다. 만료 스케줄러 골격도 SeatHoldExpirationScheduler로 맞췄다. DB seat_holds와 컬럼·제약은 유지한다. 멱등성 작업 코드 TICKET_HOLD는 엔티티명과 별도 계약이므로 유지한다.
이름 통일 후 ./gradlew clean build를 실행해 전체 테스트 210개가 통과했다.
