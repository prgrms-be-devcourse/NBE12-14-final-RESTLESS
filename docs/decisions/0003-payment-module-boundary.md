# ADR-0003. 공통 결제 모듈 경계: 결제 대상 식별과 결과 전달

- 상태: Accepted
- 날짜: 2026-11-12
- 관련: 공통 Payment, Drop TX2·TX3·TX5, 티켓팅 결제, 팬미팅 `PaymentPort` (`domains/fanmeeting/ports.md`)

## 배경

드롭, 티켓팅, 팬미팅이 공통 결제 모듈 하나로 토스페이먼츠 결제를 처리한다. 머지된 `V20261111_0920` 스키마와 각 도메인 문서 사이에 아래 문제가 있다.

1. `payments.order_id`가 공통 `orders`를 참조하는 NOT NULL FK다. 팬미팅은 자체 `fan_meeting_orders`를 쓰므로 공통 `payments`에 결제를 기록할 수 없다.
2. `payments.payment_key`가 NOT NULL이다. 토스 paymentKey는 구매자가 결제창 인증을 마친 뒤에야 받으므로, 결제 시작(READY) 시점에는 값이 없다.
3. 토스 `orderId`는 영문, 숫자, `-`, `_`, `=`로 된 6~64자 문자열이어야 한다. BIGINT 주문 ID를 그대로 쓸 수 없고, 실패 후 재시도하면 시도마다 고유한 값이 필요하다.
4. PG 승인 결과를 도메인에 어떻게 알릴지 정하지 않았다. 드롭 TX3은 결제 승인, 예약 확정, 재고 이동, 주문 PAID, 구매 슬롯 해제를 한 트랜잭션에서 확정해야 한다 (INV-09, INV-11). [ADR-0001](0001-event-handling.md) 1항은 핵심 상태 확정을 이벤트 리스너에 맡기지 못하게 한다.

## 결정

### 1. 결제 대상은 `(payable_type, payable_id)`로 식별한다

- `payments`에 `payable_type`(`DROP`, `TICKET`, `FAN_MEETING`)과 `payable_id`를 둔다. 결제 대상 테이블로 가는 물리 FK는 두지 않는다.
- 팬미팅은 `fan_meeting_orders`를 그대로 쓴다. 팬미팅 스키마와 문서는 바꾸지 않는다.
- 토스 `orderId`로 쓸 `pg_order_id VARCHAR(64) UNIQUE`를 결제 시도마다 서버가 만든다.
- `payment_key`는 NULL을 허용한다. 승인을 시작할 때(READY → PROCESSING) 채운다.
- 물리 FK가 없으므로 결제를 만들 때 결제 모듈이 해당 도메인의 Handler(`load`)로 대상의 존재, 소유자, 금액, 기한을 확인한다. 실제 계약 코드는 `com.restless.payment.application.payable`에 있다.
- 머지된 `V20261111_0920`은 수정하지 않는다. 새 마이그레이션으로 바꾼다.

### 2. 결과는 결제 모듈이 Handler를 동기 호출해 전달한다

결제 모듈이 인터페이스를 정의하고, 각 도메인이 구현한다. 결제 모듈은 결제 상태를 바꾸는 **같은 트랜잭션 안에서** Handler를 호출한다.

```java
// payment 모듈 소유. drop, ticketing, fanmeeting이 구현한다.
public interface PaymentResultHandler {
    PayableType type();
    Optional<PayableSnapshot> load(Long payableId);        // 결제 생성 시: 소유자, 금액, 상품명, 기한. 결제 불가면 empty
    ApprovalDecision onApprovalStarting(Long payableId);   // TX2: 예약 만료 검증, CONFIRMING 전환
    ApprovalDecision onApproved(Long payableId, ApprovedPayment payment); // TX3
    void onFailed(Long payableId);                         // TX4: 실패 확정, 재고 반환
    void onUnknown(Long payableId);                        // 결과 불명확: 자원 보호 상태로 전환
    void onRefunded(Long payableId);                       // TX5: 환불 확정, 재고 복원
}
```

정상 승인(TX3) 흐름:

1. 조건부 전이 `UPDATE payments SET status = 'APPROVED' WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')`. 바뀐 행이 0개면 이미 처리된 결과이므로 아무것도 하지 않는다.
2. 같은 트랜잭션에서 `handler.onApproved(...)`를 호출한다.
3. 커밋한다.
4. 커밋 후 `PaymentApprovedEvent`를 발행한다. 알림 같은 후속 작업에만 쓴다 (ADR-0001).

대사 스케줄러가 UNKNOWN, PROCESSING 건을 PG에 다시 조회해 확정할 때도 같은 Handler를 호출한다.

### 3. Handler 규칙

| 규칙 | 이유 |
|---|---|
| 업무상 받을 수 없는 승인(기한 초과 등)은 `REJECT`를 반환한다. 결제 모듈이 보상 취소(COMPENSATING)를 진행한다 | 승인된 돈을 방치하지 않는다 |
| 기술 오류는 예외로 던진다. 전체가 롤백되고 결제는 PROCESSING/UNKNOWN으로 남아 대사가 재시도한다 | UNKNOWN을 실패로 확정하지 않는다 (INV-10) |
| Handler 안에서 PG 호출이나 외부 I/O를 하지 않는다 | 트랜잭션과 잠금 시간을 짧게 유지한다 |
| 잠금 순서: 결제 행을 먼저, 도메인 행을 나중에 잠근다 | 데드락 방지 (FAIL-DR-11) |
| 도메인 쪽 상태 변경도 조건부 전이로 멱등하게 만든다 | 중복 확정, 이중 반환 방지 (INV-11) |
| 의존 방향은 도메인 → 결제 한 방향이다. 결제 모듈은 도메인 패키지를 import하지 않고, 등록된 Handler 빈을 `type()`으로 찾는다 | 순환 의존 방지, 모듈 경계 유지 |

## 검토한 대안

| 대안 | 기각 이유 |
|---|---|
| A. 결제만 커밋한 뒤 AFTER_COMMIT 이벤트로 도메인이 확정 | 커밋과 리스너 실행 사이에 서버가 종료되면 결제는 APPROVED인데 주문은 미결제로 남는다. ADR-0001 1항 위반 |
| B. 같은 트랜잭션 안의 동기 `@EventListener` | 원자성은 지켜지지만 수신자가 코드에 드러나지 않고 예외와 순서가 숨겨진다. 이벤트는 후속 작업용이라는 팀 규칙과 섞인다 |
| D. 각 도메인이 PG 호출, 결과 기록, 대사를 직접 지휘 | 세 도메인이 PG 호출, UNKNOWN 처리, 재시도를 각자 구현한다. 사용자 요청 없이 도는 대사 스케줄러가 결국 도메인을 호출해야 하므로 C 구조가 다시 필요하다 |
| 팬미팅도 공통 `orders`를 생성 | 머지된 팬미팅 스키마, 상태 머신, PR #6 문서를 바꿔야 한다. 결제 쪽 스키마만 바꾸는 편이 변경 범위가 작다 |

## 알려진 한계

- 물리 FK가 없어 잘못된 `payable_id`를 DB가 막지 못한다. 결제 생성 시 Handler `load`로 검증한다.
- Handler가 느리거나 버그가 있으면 그 도메인의 결제 확정이 지연된다. 결제는 PROCESSING/UNKNOWN으로 남으므로 돈과 재고의 정합성은 유지되고, 대사가 재시도한다. 실패는 로그와 메트릭으로 남긴다.

## 영향

- 새 마이그레이션: `payments`에 `payable_type`, `payable_id`, `pg_order_id`, `idempotency_key`, 실패 사유, `created_at`, `updated_at` 컬럼과 status CHECK를 추가한다. "대상당 진행 중인 시도 1건" 생성 컬럼 UNIQUE를 추가한다. `order_id`와 FK는 제거하고, `payment_key`는 NULL을 허용한다. `payment_refunds`에 중복 환불 방지 제약과 PG 취소 거래 키를 추가한다.
- 팬미팅 `PaymentPort`(`startAttempt`, `resolve`)는 결제 모듈이 이 구조 위에서 제공한다. 팬미팅의 `PaymentResultHandler`는 위 인터페이스에 맞춘다.
- 드롭 TX2·TX3·TX4·TX5의 도메인 쪽 단계는 Handler 구현 안에서 수행한다.

## 재검토 조건

| 조건 | 검토 대상 |
|---|---|
| Handler 실행 시간이 결제 확정 p95의 주요 원인으로 측정됨 | Handler 범위 축소, 트랜잭션 분할 |
| 결제 대상 도메인이 별도 서비스로 분리됨 | 메시지 기반 전달 (Outbox, ADR-0001 재검토) |
