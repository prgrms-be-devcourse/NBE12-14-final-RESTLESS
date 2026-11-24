# Drop 트랜잭션 경계

> 관련 코드: `backend/src/main/java/com/restless/drop/application/`
>
> 마지막 확인: 2026-11-24 (#390 D4 상태 조회와 진입에 동시 처리 상한과 `pollAfterMs` 추가, 트랜잭션 경계는 그대로. 이전: 2026-11-22 (#330 재고 버킷 분할: TX1 5번, 계측 표, 정산 대상 버킷, 입장 허가 작업의 재고 합 읽기. 2026-11-21 #325 완료 단계 타이머 추가. #322 TX1 순서 변경: 재고 UPDATE를 마지막으로. 2026-11-20 #285 TX3 롤백 뒤 상태를 `DropApprovalRollbackRecoveryIntegrationTest`, 장애 처리 문서와 대조. 용어 정리. 2026-11-19 #254 SKU별 입장 제어 구현. TX1의 재고 계측 구간은 유지한다. TX2~TX5는 결제 핸들러, 정산, 생명주기, 만료 작업 코드와 대조해 보완했다)

PG 호출 중에는 DB 트랜잭션을 열어 두지 않는다.

재고 수량의 증감(`reserved -1, sold +1` 같은 표기)은 모두 재고 점유가 기록한 버킷(`drop_reservations.bucket_no`) 한 행에 적용한다. 바뀐 행이 정확히 1이 아니면 예외로 전체를 롤백한다 (INV-03, INV-11, #330).

TX2~TX5의 드롭 쪽 단계는 결제 모듈이 같은 트랜잭션에서 호출하는 `PaymentResultHandler` 드롭 구현 안에서 수행한다 ([ADR-0003](../../decisions/0003-payment-module-boundary.md)).

## TX1. 재고 점유 + 주문 생성

하나의 MySQL 트랜잭션에서:

1. 사용자별 활성 구매 거래 검증, 제어. 같은 멱등성 키의 기존 결과는 입장 허가 검증 없이 반환한다.
   입장 제어 사용 시 해당 회원과 드롭의 최신 대기 행을 잠그고 유효한 ADMITTED 입장 허가와 요청 SKU 일치를 확인한다.
2. 공통 Order 생성 (OrderItem, 배송, 가격 스냅샷 포함)
3. DropReservation 생성 (`order_id`가 NOT NULL FK라 주문을 먼저 만든다). 기한은 이 시각 + TTL이다
4. 입장 제어 사용 시 입장 허가를 CONSUMED로 바꾸고, 구매 슬롯에 이 예약을 기록한다. 여기까지의 쓰기를 DB로 flush한다
5. 재고 버킷 하나에서 조건부 점유. 가장 많이 경합하는 재고 락이 UPDATE와 Commit 사이에만 걸리도록 마지막에 둔다 (#322). 아래 5-1~5-5 ([ADR-0005](../../decisions/0005-drop-inventory-buckets.md), #330)
6. 락 대기 중 판매 종료나 입장 허가 기한이 지났는지 시계로만 다시 확인하고 Commit. 지났으면 2~5번이 모두 롤백된다

5번 재고 버킷 점유 (격리 수준 READ_COMMITTED):

- 5-1. SKU의 `bucket_count`(K)를 잠그지 않고 읽고 시작 버킷 `b0`를 0~K-1에서 무작위로 고른다. 머리 행은 갱신되지 않아 기다리지 않는다
- 5-2. 고르기: `available > 0`이고 `bucket_no >= b0`인 첫 버킷을 `FOR UPDATE SKIP LOCKED`로 고른다. 없으면 `bucket_no < b0`로 한 번 더 한다(`b0 = 0`이면 생략). 기다리지 않고, 고른 행 하나만 잠근다
- 5-3. 고른 버킷에 한 문장 UPDATE로 `available - 1`, `reserved + 1`을 하고 같은 문장에서 점유의 `bucket_no`를 기록한다. 이미 잠근 버킷이라 기다리지 않는다. 0행이면 버그로 보고 예외로 롤백한다. 이 UPDATE가 점유 행의 `bucket_no`를 바꾸므로 엔티티 매핑은 `updatable = false`다
- 5-4. 5-2가 비면 재고 있는 버킷이 모두 다른 트랜잭션에 잠긴 것인지 본다. 잠그지 않는 한 문장 읽기로 커밋된 재고가 있는 버킷을 `b0`부터 순서로 본다. 이미 기다린 버킷은 뺀다. 없으면 `DROP_SOLD_OUT`
- 5-5. 있으면 그 버킷에 5-3의 UPDATE를 하고 락을 기다린다. 1행 이상이면 성공이다. 0행이면 그 버킷은 비었고, 그 락은 커밋까지 남는다. 5-2로 돌아간다. 버킷마다 한 번, 최대 K번 기다린다

교착이 없는 이유: 기다리는 곳은 5-5뿐이고, 커밋된 재고를 본 버킷만 기다린다. 기다리는 동안 쥔 다른 버킷 락은 5-5에서 남은 락뿐이고 그 버킷은 쥐고 있는 동안 계속 0이다. 그래서 대기 순환을 만들 수 없다. 근거와 실험은 #327 설계와 검증 기록에 있다.

품절은 5-4의 한 문장 읽기에서 모든 버킷의 커밋된 재고가 0일 때만 나온다. 그 읽기 뒤에 반환이 커밋되면 품절 응답 직후 재고가 생길 수 있다. 단일 행에서도 같은 창이 있었다 (결정 D3).

5-5에서 남은 락을 쥔 TX1은 커밋까지 그 버킷을 잡는다. 그동안 그 버킷의 확정과 반환이 기다린다. 정확성 문제는 아니고 정산 지연이다.

Notion 원본은 재고 예약이 1번이다. #322 전에는 슬롯 확인 직후였고, 그 뒤 주문, 예약 INSERT와 슬롯, 입장 허가 UPDATE가 재고 행 락을 쥔 채 실행됐다.
재고 행 락 대기만큼 예약 기한이 짧아진다. 평소 수 ms이고 경합이 심할 때 수 초다.

하나라도 실패하면 전체 롤백한다.

계측 (#216, #330): 재고 버킷 잠금을 타이머와 카운터로 잰다. 태그 값은 정해진 몇 개뿐이다.

| 이름 | 구간 | 태그 |
|---|---|---|
| `drop.inventory.hold.update` | 5-2 시작부터 5-3 또는 5-5의 성공 응답까지. 5-5 대기를 포함한다. 품절이면 5-4 판정까지 | `result`: held, sold_out |
| `drop.inventory.lock.held` | 점유한 버킷의 락을 얻은 시각부터 트랜잭션 종료까지. 5-2 성공이면 5-2 응답, 5-5 경로면 5-5 응답부터. 5-3 왕복이 들어간다 | `outcome`: committed, rolled_back, unknown |
| `drop.inventory.lock.completion` | `lock.held` 중 트랜잭션 완료 단계. 완료 시작(flush, JDBC commit 또는 rollback)부터 끝까지 (#325) | `outcome`: committed, rolled_back, unknown |
| `drop.reservation.duration` | TX1 전체 | `result`: created, replayed, sold_out, not_on_sale, active_exists, rejected, error |
| `drop.inventory.hold.path` (카운터) | 5번의 경로. picked는 기다리지 않고 성공, waited는 5-5를 한 번 이상 거쳐 성공, sold_out은 품절 | `path`: picked, waited, sold_out |

`lock.held`에서 `lock.completion`을 빼면 락을 얻은 뒤 커밋 전까지의 시간이다. 5-3 왕복이 들어가므로 #330 전 값과 구간이 다르다. 전후 비교는 같은 날 K=1 실행과 한다. `drop.inventory.lock.held`는 앱에서 잰다. DB가 잠금을 쥔 시간에 응답과 커밋 왕복이 더해진다. 품절은 버킷 락을 얻지 않아 이 타이머에 들어가지 않는다.

## TX2. PG 승인 시작

짧은 MySQL 트랜잭션에서:

1. 예약 상태와 만료 검증 (예약 행을 잠근 뒤 읽은 현재 시각으로 판정)
2. RESERVED → CONFIRMING, 예약에 이 시도의 `paymentId` 기록 (`current_payment_id`)
3. Order → PAYMENT_CONFIRMING
4. Payment 승인 시도 상태 기록 (READY → PROCESSING, `approval_started_at`)
5. 예약이 RESERVED가 아니거나 기한이 지났으면 아무것도 바꾸지 않고 `DROP_NOT_PAYABLE`로 거절한다
6. Commit

그다음 트랜잭션 밖에서 PG 승인 API를 호출한다.

결제 준비 단계의 `load`도 예약 행을 잠그고, RESERVED일 때만 결제 대상(금액, 주문명, 기한)을 돌려준다.

재결제도 같은 흐름이다. 새 `payments` 행이 생기고 `current_payment_id`가 새 시도로 바뀐다.

### TX2-b. 승인 결과 불명확 (`onUnknown`)

결제 모듈 트랜잭션에서:

1. 실패 확정이 아닌 불명확한 결과이고 현재 시도(`current_payment_id`)의 CONFIRMING일 때만 예약 → RECONCILING
2. Order → PAYMENT_REVIEW
3. 재고와 구매 슬롯은 그대로 둔다 (예약 보호). 일반 만료 작업은 반환하지 않는다 (INV-10)
4. 조건이 맞지 않으면 아무것도 바꾸지 않는다

이후 결제 모듈의 미완료 결제 재확인 작업이 PG를 조회해 TX3, TX4-a, 보상 취소 중 하나로 이어 간다.

## TX3. 구매 확정

PG 승인이 확인된 뒤 하나의 트랜잭션에서:

1. 결제 승인 결과 기록
2. 예약 행을 잠그고 결과의 `paymentId`가 예약의 `current_payment_id`인지 확인. 아니면 `DROP_STALE_ATTEMPT`로 거절한다 (결제 모듈이 보상 취소). 이 경우 예약은 건드리지 않는다 (INV-15)
3. 예약이 이미 CONFIRMED면 중복 결과로 보고 아무것도 바꾸지 않고 수락한다 (INV-11)
4. 예약이 CONFIRMING 또는 RECONCILING이 아니면 `DROP_INVALID_STATE`로 거절한다
5. 승인 금액이 서버 주문 금액과 다르면 예약을 COMPENSATING으로 바꾸고 `DROP_AMOUNT_MISMATCH`로 거절한다 (결제 모듈이 보상 취소, INV-08)
6. 예약 CONFIRMED
7. reserved -1, sold +1
8. 주문 PAID
9. 구매 슬롯 해제 (같은 사용자가 다시 구매할 수 있게). 슬롯의 `active_reservation_id`가 이 예약일 때만 비운다
10. Commit

승인 시각(`approvedAt`)이 예약 기한 뒤여도 확정한다. 기한은 TX2에서 판정했다.

TX3가 롤백되면 결제는 `PROCESSING`, 예약은 `CONFIRMING`, 주문은 `PAYMENT_CONFIRMING`으로 남는다. 재고와 슬롯도 그대로다. 미완료 결제 재확인 작업이 PG를 다시 조회해 TX3를 다시 실행한다. 상세와 한계는 [장애 처리 문서](../../operations/failure-handling.md#승인-결과-반영-실패와-복구-269-270-273)에 있다.

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

만료 작업(`dropReservationExpiryJob.runOnce()`)은 후보를 한 번에 100건 읽고, 건마다 별도 트랜잭션(REQUIRES_NEW)으로 처리한다.
건별 트랜잭션은 다음을 수행한다.

1. 예약 행을 잠근 뒤 읽은 현재 시각으로 상태가 RESERVED이고 기한이 지났는지 조건부 검증
2. EXPIRED로 전환
3. reserved -1, available +1
4. Order CANCELLED
5. 구매 슬롯 해제
6. Commit

재결제 대기로 돌아온 RESERVED 예약도 같은 작업이 닫는다.
CONFIRMING, RECONCILING, COMPENSATING 상태의 거래는 일반 만료 작업이 반환하지 않는다.
TX2와 TX4-b는 같은 예약 행 잠금으로 직렬화한다. 기한 직전의 재결제 시작과 만료가 겹쳐도 한쪽만 반영된다.
한 건이 실패하면 오류 로그와 `drop.expiry.processed` 메트릭(`result`: expired, skipped, failed)을 남기고 다음 건으로 넘어간다.

### TX4-c. 결제 전 구매 취소 (`cancel`)

동작과 오류는 [API 계약 drop.md D3](../../contracts/api/drop.md)를 따른다. 예약 행을 잠그고 RESERVED일 때만 RELEASED로 바꾸며, 재고, 주문, 슬롯 갱신은 TX4-b와 같은 `DropReservationSettlement.release`를 쓴다. 승인 시작(TX2)과는 같은 예약 행 잠금으로 직렬화된다.

## TX5. 전액 환불

### TX5-a. 환불 요청 시작 (`onRefundRequesting`, `onRefundRejected`)

결제 모듈 트랜잭션에서:

1. 환불 종류가 CUSTOMER_REFUND이고 현재 시도의 예약이 CONFIRMED인지 확인. 아니면 `DROP_NOT_REFUNDABLE`로 거절한다
2. Order PAID → REFUND_PENDING. 이미 REFUND_PENDING이면 중복 요청으로 보고 수락한다
3. Order가 REFUND_REVIEW이면 PAID를 거쳐 REFUND_PENDING으로 다시 시작한다 (재요청)
4. PG가 취소를 확정 거절하면(`onRefundRejected`) Order → REFUND_REVIEW. 재고는 건드리지 않는다
5. Commit

### TX5-b. 전액 환불 확정 (`onRefunded`)

PG 취소가 확인된 뒤 하나의 트랜잭션에서:

1. 환불 결과 기록. 결과의 `paymentId`가 예약의 `current_payment_id`가 아니면 변경 없이 오류 로그와 메트릭
2. 환불 종류에 따라 분기
   - `CUSTOMER_REFUND`: 예약 CONFIRMED → RESTORED, sold -1, available +1, Order REFUNDED. 이미 RESTORED면 중복으로 무시한다. Order가 REFUND_PENDING 또는 REFUND_REVIEW가 아니면 변경 없이 이상 상태로 기록한다. 구매 슬롯은 건드리지 않는다 (확정 때 이미 해제했고, 그 사이 새 구매가 슬롯을 소유할 수 있다)
   - `COMPENSATION`: 예약 COMPENSATING → RELEASED, reserved -1, available +1, Order CANCELLED, 구매 슬롯 해제. 이미 RELEASED면 중복으로 무시한다
3. Commit

종류와 예약 상태가 맞지 않으면 재고를 바꾸지 않고 오류 로그와 메트릭(`drop.payment.result`, `event=refunded`, `result=anomaly`)을 남긴다.

PG 취소 결과가 불명확하면 재고를 복원하지 않는다.

## 공통 규칙 (TX2~TX5)

- 재고, 주문, 구매 슬롯 갱신은 `DropReservationSettlement` 한 곳에서 수행한다 (confirm, release, restore). 이 서비스는 호출자 트랜잭션 안에서만 실행된다 (MANDATORY). 재고 UPDATE가 정확히 1행을 바꾸지 않으면 예외로 전체를 롤백한다.
- 구매 슬롯 해제는 슬롯의 `active_reservation_id`가 그 예약일 때만 한다. 다른 예약을 가리키면 건드리지 않는다.
- 모든 전이는 예약 행 잠금 아래 조건부로 수행해 중복 확정과 중복 반환을 막는다 (INV-11).
- 현재 시도(`current_payment_id`)가 아닌 결제 결과는 예약, 재고, 주문을 바꾸지 않는다 (INV-15).
- 드롭 결제 콜백 메트릭 `drop.payment.result`(`event`, `result` 태그)는 결제 트랜잭션이 커밋된 뒤에만 센다. 메트릭 실패는 로그와 `drop.payment.metric.failure`로 남기고 결제 트랜잭션에 영향을 주지 않는다.

## D4. 입장 제어

### SKU별 입장과 종료 (#254)

정책은 [policy.md 7-1절](policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)이다. 구매 입장 제어, 입장 허가 (ADMITTED), DB row lock 대기는 서로 다르다. 종료 상태(`UNAVAILABLE`)와 오류 코드는 확정이다. 동시성은 `drop_queue_gates`의 SKU별 행으로 직렬화한다. 게이트만 `FOR UPDATE SKIP LOCKED`로 읽고, 이미 사용 중인 SKU는 건너뛴다.

- 진입: SKU(`variantId`)를 받아 그 SKU가 그 드롭 소속인지, 판매 중인지, `available > 0`인지 확인한다. `available = 0`이면 행을 만들지 않고 409 `DROP_SOLD_OUT`(현재 구매 불가)을 돌려준다. 이 확인은 SKU 버킷 `available` 합을 잠그지 않고 한 문장으로 읽는다. 구매 슬롯으로 같은 회원의 진입을 직렬화하고 최신 대기 행에 락 획득을 한다.
  - 같은 회원의 살아 있는 대기가 같은 SKU면 그대로 돌려준다. 다른 SKU면 새 SKU의 `available`과 소속을 먼저 확인한 뒤(거절이면 기존 대기를 건드리지 않는다), 기존 행(`WAITING`이든 `ADMITTED`든)을 `CLOSED`로 바꾸고 flush한 뒤 새 `WAITING` 행을 만든다. 모두 한 트랜잭션이다.
  - 페이지를 나가면 상태 조회가 끊긴다. 저장된 heartbeat가 40초보다 오래되면 조회는 404를 반환하고 입장 허가 작업이 정리한다. 별도 해제 요청은 없다.
- 대기열 진입과 상태 조회는 컨트롤러 앞의 인터셉터에서 서버별 동시 처리 상한을 먼저 확인한다 (2026-11-24 사용자 위임 결정, #390). 상한을 넘으면 트랜잭션과 커넥션을 쓰기 전에 503 `DROP_QUEUE_BUSY`로 끝낸다. 허용치는 요청이 정상으로 끝나든 예외로 끝나든 돌려준다. TX1, 결제, 결제 확정 경로에는 걸지 않는다. WAITING 응답에는 순번으로 정한 `pollAfterMs`를 싣는다. 순번 계산과 heartbeat 기록은 바꾸지 않았다.
- 상태 조회는 최신 대기와 판매를 transaction 없이 함께 읽는다. 이탈과 판매 종료 WAITING은 404, 기한이 된 ADMITTED는 EXPIRED로 응답한다. 이 판정은 행을 바꾸지 않는다.
- 저장 시각에서 10초를 넘긴 WAITING만 별도 transaction의 조건부 UPDATE로 heartbeat를 기록한다. UPDATE는 같은 id의 WAITING, 저장 시각 기준 40초 이내와 기록 간격을 다시 확인한다. 0행이면 최신 행을 다시 읽어 답한다.
- 2026-11-24 run4와 run5에서 commit당 SQL이 약 11.7문장이었다. P1과 P4 결정으로 읽기 경로의 격리 수준 변경과 COMMIT을 없앴다. 주기적인 ADMITTED 만료 정리 작업은 없다. expired 메트릭은 재진입에서 실제 EXPIRED로 바뀔 때 기록한다.
- `position`은 같은 SKU의 `WAITING` 안에서의 입장 순번이다. 이미 종료된 최신 행은 그 상태(`UNAVAILABLE`)를 그대로 돌려준다. 어떤 경우에도 요청을 붙잡지 않는다.
- 입장 허가 작업: 대기자가 있는 SKU마다 아래 짧은 batch transaction을 실행한다. 종료 batch 크기는 100이고 batch마다 새 transaction으로 재고를 읽는다. 대기를 유지하려고 트랜잭션, 커넥션, thread를 쥐지 않는다.

입장과 종료 트랜잭션 (SKU 하나, READ_COMMITTED):

1. 같은 SKU의 입장 트랜잭션과 종료 트랜잭션끼리 한 번에 하나만 실행되도록 DB 락으로 직렬화한다. 이미 처리 중이면 기다리지 않고 그 SKU를 건너뛴다 (아래 동시성 요구).
2. 미사용 유효 ADMITTED 수 `c`를 센다 (`status = 'ADMITTED' AND now < admitted_until`).
3. 그다음 SKU 버킷 `available_quantity` 합을 한 문장으로 읽는다. `reserved_quantity`는 보지 않는다 (#330).
4. 분기:
   - `available = 0`: 이 SKU의 `WAITING`과 미사용 `ADMITTED`를 종료 상태로 바꾼다. 행을 잠근 뒤 아직 그 상태인 행만 바꾸는 조건부 UPDATE다. 대기 행 락은 짧게 끝나므로(TX1, 조건부 heartbeat 쓰기) 다른 트랜잭션이 쥔 대기 행은 `SKIP LOCKED`로 건너뛰지 않고 락을 기다린다. 건너뛰면 살아 있는 행이 남아 INV-17을 어긴다. 기다리지 않고 건너뛰는 것은 SKU 게이트 락뿐이다. `CONSUMED`인 행과 그 점유는 건드리지 않는다. 한 트랜잭션이 너무 커지지 않도록 일정 개수씩 나눠 처리하고, 나눈 묶음마다 `available`을 다시 읽어 0이 아니면 거기서 멈춘다.
   - `available - c <= 0`: 남은 재고가 이미 입장 허가로 덮였다. 입장시키지 않고 끝낸다. 대기는 유지하되 heartbeat 이탈과 판매 종료 WAITING은 100행씩 별도 정리한다. 허가 여유가 없어도 이 정리는 생략하지 않는다.
   - 그 밖: `n = min(available - c, admit-per-run)`. 같은 SKU의 `WAITING`을 `id` 오름차순으로 `n`명까지 `ADMITTED`로 바꾼다. 이탈자는 CLOSED로 닫고 건너뛴다.
5. Commit

동시성 요구 (INV-16, INV-17):

- 직렬화가 없으면 두 입장 트랜잭션이 같은 `available`과 `c`를 읽고 각자 `n`명을 입장 허가로 전이해 `available`을 넘긴다. `SKIP LOCKED`로 대기 행이 겹치지 않게 하는 것만으로는 이를 막지 못한다.
- DB 락(DECIDED 2026-11-19)으로 직렬화한다. 서버 안 메모리 락은 서버 2대 이상에서 INV-16을 지키지 못해 쓰지 않는다. 수단은 `drop_queue_gates(variant_id PK/FK)` 행이다. SKU 생성 transaction과 migration에서 게이트를 만든다. 이름 있는 락과 달리 commit 또는 rollback으로 자동 해제되어 pooled connection의 락 누수 위험을 피한다. 두 수단 모두 "이미 잡혀 있으면 기다리지 않고 건너뜀"이 가능해야 한다. 재고 버킷은 TX1이 가장 많이 경합하는 행이므로 수단으로 쓰지 않는다. TX1은 이 락을 잡지 않고, 입장 허가 작업의 재고 읽기를 TX1이 기다리게 만들지 않는다.
- 건너뛴 SKU의 처리는 다음 주기로 밀린다. 지연은 입장 허가 작업 한 번의 주기만큼이다.
- 3번에서 `c`를 먼저 읽고 `available`을 나중에 읽는다. TX1은 `available -1`과 `ADMITTED -1`(CONSUMED)을 같은 커밋에서 하므로 둘의 차는 TX1로 줄지 않는다. 순서를 반대로 읽으면 사이에 TX1이 끼어 초과 허가할 수 있다. 이 순서에서는 TX1이 끼면 적게 발급할 뿐이다.
- 직렬화 대기와 DB row lock 대기는 입장 허가 작업 안에서 짧게 끝나야 한다. 사용자의 입장 순번을 정하는 데 쓰지 않는다 (순서는 `id`).

게이트 행이 없을 때의 복구:

- 게이트 락 결과는 LOCKED, BUSY, MISSING이다. SKIP LOCKED 결과가 비면 잠그지 않는 존재 조회로 BUSY와 MISSING을 구분한다.
- MISSING이면 batch transaction을 끝낸 뒤 ERROR 로그와 태그 없는 `drop.queue.gate.missing`을 남긴다. 별도 REQUIRES_NEW transaction에서 INSERT IGNORE로 게이트를 만들고 batch를 한 번 재시도한다. 재시도도 비면 건너뛴다.
- 정상 LOCKED와 BUSY 경로에는 INSERT가 없다. TX1은 이 복구 경로를 쓰지 않는다.
- 동시 복구의 INSERT는 PK 충돌 처리 때문에 짧게 기다릴 수 있다. 그래도 admission transaction을 쥔 채 기다리지 않는다.

TX1과의 관계:

- TX1 조건부 재고 UPDATE(`available - 1`, `reserved + 1`)는 재고 확보의 최종 검증이다. 입장 허가가 있어도 재고가 없으면 품절(409 `DROP_SOLD_OUT`)이다.
- TX1은 그 SKU의 입장 허가이고 아직 `ADMITTED`인 것만 받아들인다. 다른 SKU의 입장 허가나 종료된 입장 허가로 점유할 수 없다. 입장 허가 소비(CONSUMED)는 재고 점유 성공과 같은 트랜잭션이다 (현재와 같다).
- 같은 멱등성 키의 기존 결과는 입장 허가 검증 없이 반환한다 (현재와 같다). 대기 종료가 이미 성공한 점유의 재시도 응답을 바꾸지 않는다.
- 결제 성공(TX3)은 `reserved -1`, `sold +1`이고 `available`은 바뀌지 않는다. 진입과 입장 허가가 재개되지 않는다.
- 점유 반환(TX4 결제 실패 기한 뒤, TX4-b 만료, TX5 `COMPENSATION`)은 `available +1`이다. 판매 기간 안이면 다음 진입과 다음 입장 실행부터 재개된다. 종료된 입장 순번과 입장 허가는 복구하지 않는다.
- PG 결과가 불명확한 거래는 TX4, TX5가 일어나지 않으므로 `reserved`가 유지되고 재고가 반환되지 않는다. 입장 제어가 이를 실패로 보지 않는다.
- 손님 환불(`CUSTOMER_REFUND`)의 `sold -1, available +1`도 같은 재개 조건이다.

### 실패 시나리오와 기대 결과

재고가 0이 되는 시점의 대기 종료, 입장 허가 전이, 진행 중인 TX1, 재고 반환이 겹치는 경우다. 모든 경우에서 이미 성공한 점유는 취소하지 않고, 종료된 입장 허가로 신규 점유를 허용하지 않는다.

| ID | 겹치는 일 | 기대 결과 | 근거 |
|---|---|---|---|
| F1 | 종료 트랜잭션과, 같은 SKU의 마지막 재고를 가져가는 진행 중인 TX1 | TX1이 대기 행을 먼저 잠그면 점유가 커밋되고 행은 `CONSUMED`다. 종료는 그 행을 건드리지 않는다. 종료가 먼저 행을 바꾸면 TX1은 입장 허가 검증에서 거절된다. 어느 쪽이든 점유와 재고 수량은 어긋나지 않는다 | 종료는 잠근 뒤 조건부 UPDATE, 재고 확보는 TX1 UPDATE가 최종 검증 |
| F2 | 입장 트랜잭션 둘 이상이 같은 SKU를 동시에 처리 | 허가 합계가 `available`을 넘지 않는다 | DB 락 직렬화 (DECIDED) |
| F3 | 입장이 `c`와 `available`을 읽는 사이에 TX1이 커밋 | 적게 허가할 수는 있어도 많이 허가하지 않는다 | 읽기 순서 `c` 먼저 |
| F4 | 입장 허가 작업이 전이하는 사이에 다른 TX1이 재고를 줄임 | TX1은 자기 입장 허가 몫(`available -1`, `ADMITTED -1`)만 줄이므로 허가분은 여전히 `available` 이하다. 어긋난 입장 허가가 있다면 다음 입장 실행이 `available = 0`을 보고 종료한다. 그 전 TX1은 조건부 UPDATE로 품절을 돌려준다 | INV-16, TX1 최종 검증 |
| F5 | 종료가 `available = 0`을 읽은 뒤 커밋 전에 재고 반환 | 이미 종료한 행은 복구하지 않는다. 묶음마다 `available`을 다시 읽어 남은 묶음은 종료하지 않는다. 사용자는 새로 진입한다. 점유나 재고에는 영향이 없다 | 정책 규칙 6 |
| F6 | 진입이 `available > 0`을 읽고 행을 만드는 사이에 재고가 0이 됨 | `WAITING`이 잠시 남는다. 다음 입장 실행이 종료한다. 그 사이 TX1은 입장 허가가 없어 점유할 수 없다 | 진입 확인은 잠금 없는 읽기. 지연은 입장 허가 작업 한 번의 주기 안 |
| F7 | 재고 반환과 입장이 겹침 | 반환 전에 종료된 사용자는 복구되지 않는다. 반환 뒤 진입한 사용자부터 입장 허가로 전이한다. 반환 전 진입한 사용자는 `available = 0`이라 이미 거절됐다 | 정책 규칙 6 |
| F8 | PG 결과 불명확인 점유가 `available = 0`의 원인 | 재고는 반환되지 않고 입장 제어는 현재 구매 불가를 유지한다. 입장 제어 쪽에서 점유 상태를 바꾸지 않는다. 미완료 결제 재확인 작업이 실패를 확정하면 그때 TX4가 재고를 반환하고 재개된다 | INV-10 |
| F9 | 종료된 입장 허가로 점유 요청 | 403 `DROP_ADMISSION_REQUIRED`. 재고 수량은 바뀌지 않는다. 이후 재고가 반환돼도 그 입장 허가는 살아나지 않는다 | INV-06 |
| F10 | 종료 중에 같은 멱등성 키로 성공한 점유를 재요청 | 기존 결과를 그대로 반환한다 | INV-07, TX1 1번 |
| F11 | 종료와 결제 승인 시작(TX2), 재결제가 겹침 | 대기 종료는 예약 행을 잠그지 않고 바꾸지 않는다. 결제, 기한, 재결제는 [policy.md 8절](policy.md#8-재고-점유-정책) 그대로다 | 정책 규칙 7 |
| F12 | 상태 조회가 종료와 겹침 | 종료가 먼저면 종료 상태를 본다. 조회가 먼저면 WAITING을 보고 다음 조회에서 종료를 볼 수 있다. 종료 후 도착한 heartbeat UPDATE는 0행이고 최신 상태를 다시 읽는다 | 조건부 UPDATE |

`drop.queue.admitted`, `drop.queue.unavailable`, `drop.queue.skipped`는 태그 없는 합계이며 commit 후 증가한다. 작업 실패는 로그와 `drop.queue.failure`, 후속 메트릭 실패는 로그와 `drop.queue.metric.failure`로 남긴다. 메트릭은 태그에 SKU ID를 넣지 않는다 (끝없이 늘어나는 값). SKU별 입장 수나 현재 구매 불가 SKU 수는 태그 없는 합계나 로그로 본다.
