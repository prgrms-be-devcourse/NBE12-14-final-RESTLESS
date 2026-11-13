# Drop 트랜잭션 경계

> 관련 코드: `backend/src/main/java/com/restless/drop/application/`
>
> 마지막 확인: 2026-11-14 (#41 탈퇴 제한 구현과 대조)

PG 호출 중에는 DB 트랜잭션을 열어 두지 않는다.

TX2~TX5의 드롭 쪽 단계는 결제 모듈이 같은 트랜잭션에서 호출하는 `PaymentResultHandler` 드롭 구현 안에서 수행한다 ([ADR-0003](../../decisions/0003-payment-module-boundary.md)).

## TX1. 재고 예약 + 주문 생성

하나의 MySQL 트랜잭션에서:

1. 사용자별 활성 구매 거래 검증, 제어. 같은 멱등성 키의 기존 결과는 구매권 검증 없이 반환한다.
   대기열 사용 시 해당 회원·드롭의 최신 대기 행을 잠그고 유효한 ADMITTED 구매권을 확인한다.
2. SKU 재고 조건부 예약 (Notion 원본은 이 단계가 1번이다. 경합이 심한 재고 행의 잠금 시간을 줄이려고 슬롯 확인 뒤로 옮겼다)
3. 공통 Order 생성 (OrderItem, 배송, 가격 스냅샷 포함)
4. DropReservation 생성 (`order_id`가 NOT NULL FK라 주문을 먼저 만든다)
5. 구매 슬롯에 이 예약을 기록
6. 대기열 사용 시 구매권 기한을 다시 확인하고 CONSUMED로 바꾼다. 재고·주문·슬롯과 함께 Commit

하나라도 실패하면 전체 롤백한다.

## TX2. PG 승인 시작

짧은 MySQL 트랜잭션에서:

1. 예약 상태와 만료 검증 (예약 행을 잠근 뒤 읽은 현재 시각으로 판정)
2. RESERVED → CONFIRMING, 예약에 이 시도의 `paymentId` 기록 (`current_payment_id`)
3. Order → PAYMENT_CONFIRMING
4. Payment 승인 시도 상태 기록 (READY → PROCESSING, `approval_started_at`)
5. Commit

그다음 트랜잭션 밖에서 PG 승인 API를 호출한다.

재결제도 같은 흐름이다. 새 `payments` 행이 생기고 `current_payment_id`가 새 시도로 바뀐다.

## TX3. 구매 확정

PG 승인이 확인된 뒤 하나의 트랜잭션에서:

1. 결제 승인 결과 기록
2. 결과의 `paymentId`가 예약의 `current_payment_id`인지 확인. 아니면 REJECT (결제 모듈이 보상 취소)
3. 예약 CONFIRMED
4. reserved -1, sold +1
5. 주문 PAID
6. 구매 슬롯 해제 (같은 사용자가 다시 구매할 수 있게)
7. Commit

승인 시각(`approvedAt`)이 예약 기한 뒤여도 확정한다. 기한은 TX2에서 판정했다.

비핵심 후속 작업은 커밋 후 Spring Event Listener로 처리한다. Outbox 기록은 넣지 않는다 ([ADR-0001](../../decisions/0001-event-handling.md)).

## TX4. 결제 실패, 예약 만료

### TX4-a. 결제 실패 확정 (`onFailed`)

하나의 트랜잭션에서:

1. 예약 행 잠금, 상태가 CONFIRMING 또는 RECONCILING인지 조건부 검증
2. 실패한 시도의 `paymentId`가 `current_payment_id`인지 확인. 아니면 아무것도 바꾸지 않는다
3. 현재 시각이 예약 기한 전이면:
   - 예약 → RESERVED, Order → PENDING_PAYMENT
   - 재고와 구매 슬롯은 그대로 둔다 (재결제 대기)
4. 기한과 같거나 뒤면:
   - 예약 → RELEASED, reserved -1, available +1
   - Order CANCELLED, 구매 슬롯 해제
5. Commit

### TX4-b. 예약 만료 (만료 작업)

하나의 트랜잭션에서:

1. 예약 상태가 RESERVED이고 기한이 지났는지 조건부 검증
2. EXPIRED로 전환
3. reserved -1, available +1
4. Order CANCELLED
5. 구매 슬롯 해제
6. Commit

재결제 대기로 돌아온 RESERVED 예약도 같은 작업이 닫는다.
CONFIRMING, RECONCILING, COMPENSATING 상태의 거래는 일반 만료 작업이 반환하지 않는다.
TX2와 TX4-b는 같은 예약 행 잠금으로 직렬화한다. 기한 직전의 재결제 시작과 만료가 겹쳐도 한쪽만 반영된다.

## TX5. 전액 환불 확정 (`onRefunded`)

PG 취소가 확인된 뒤 하나의 트랜잭션에서:

1. 환불 결과 기록
2. 환불 종류에 따라 분기
   - `CUSTOMER_REFUND`: 예약 CONFIRMED → RESTORED, sold -1, available +1, Order REFUNDED
   - `COMPENSATION`: 예약 COMPENSATING → RELEASED, reserved -1, available +1, Order CANCELLED, 구매 슬롯 해제
3. Commit

종류와 예약 상태가 맞지 않으면 재고를 바꾸지 않고 오류 로그와 메트릭을 남긴다.

PG 취소 결과가 불명확하면 재고를 복원하지 않는다.

## D4. 대기열

- 진입: 기존 구매 슬롯 행으로 회원·드롭별 동시 요청을 직렬화한다. 최신 대기를 잠가 이탈·만료를 판정한 뒤, 살아 있는 대기를 반환하거나 새 WAITING을 만든다.
- 조회: 최신 대기를 잠그고 이탈·만료를 판정한 뒤 heartbeat를 갱신한다. CLOSED 전이를 커밋한 뒤 API가 404를 반환한다.
- 입장: 드롭별 READ_COMMITTED 트랜잭션에서 진입 순 WAITING 행을 `FOR UPDATE SKIP LOCKED`로 가져온다. 이탈자는 CLOSED로 닫고 건너뛰며, 설정한 인원까지 ADMITTED로 바꾼다.
- 메트릭 카운터는 트랜잭션 커밋 뒤 증가한다. `drop.queue.waiting`은 유효 heartbeat가 있는 WAITING 전체 합계이며 드롭 태그가 없다.
