# FanMeeting 외부 Port와 가짜 Adapter

## 1. Port

```java
interface PaymentPort {
    PaymentAttemptResult startAttempt(StartPaymentAttemptCommand command);
    PaymentResolutionResult resolve(String payableType, Long payableId);
}
record StartPaymentAttemptCommand(String payableType, Long payableId, Long userId,
                                  long amount, Instant expiresAt, String idempotencyKey) {}
record PaymentAttemptResult(Long paymentId, String attemptId, AttemptStatus status,
                            String checkoutUrl /* READY일 때만 */, boolean created) {}
// 거절(IN_PROGRESS, UNDER_REVIEW, EXPIRED, ORDER_NOT_PAYABLE)은 예외로 전달한다
enum AttemptStatus { READY, PROCESSING, UNKNOWN, APPROVED, FAILED }
enum PaymentResolution { NOT_STARTED, APPROVED, NOT_APPROVED_FINAL, PROCESSING, UNKNOWN }
record PaymentResolutionResult(PaymentResolution resolution, Instant approvedAt,
                               String attemptId, long approvedAmount,
                               boolean finalDecision /* true일 때만 만료 판정에 쓴다 */) {}

interface MemberPort {
    Instant joinedAt(Long userId);                         // users.created_at. 조회 실패면 예외
    Set<Long> activeMemberIds(Collection<Long> userIds);   // 탈퇴하지 않은 회원 (users.status = ACTIVE)
}

interface NotificationRequestPort { void request(NotificationRequest request); }
```

## 2. 결제 모듈 계약 (P0 가짜가 지킬 최소 규칙)

1. **한 주문에 끝나지 않은 시도(READY·UNKNOWN)는 1개.** 다른 키로 오면 READY는 `IN_PROGRESS`, UNKNOWN은 `UNDER_REVIEW`로 거절하고, 이미 APPROVED 시도가 있으면 `ORDER_NOT_PAYABLE`로 거절한다. 시도가 없거나 모두 FAILED면 새 시도를 만든다.
2. **같은 `Idempotency-Key`면 같은 시도를 돌려준다**(현재 상태와 함께).
3. **`expiresAt`이 지났으면 새 시도를 만들지 않는다.**
4. **`resolve`의 최종 판정은 결제 모듈이 정한다.** `PaymentResolutionResult.finalDecision`이 `true`일 때만 팬미팅이 만료한다. 결제 모듈은 같은 주문 잠금 안에서, 기한이 지났으면 READY 시도를 FAILED로 닫고 `finalDecision = true`를 준다. 그 뒤로 그 주문의 시도는 승인되지 않는다. 기한 전이면 `finalDecision = false`.
5. **판정 우선순위:** APPROVED > UNKNOWN > PROCESSING > NOT_APPROVED_FINAL(모두 FAILED) > NOT_STARTED(시도 없음 또는 READY만).

실제 결제 모듈과의 세부 계약(승인 전 PROCESSING 전환, 결제창 URL 재발급, 운영자 수동 결정 반영, 시계·버전 대조)은 **P1에서 결제 담당과 정한다**(담당자 문서 `PAYMENT_CONTRACT_REQUEST.md`).

## 3. 가짜 Payment Adapter (`local`/`test`)

**목적:** 테스트에서 "승인 / 실패 / 모름"을 마음대로 만들어 팬미팅 쪽 로직을 검증하는 대역. 실제 결제 모듈이 오면 통째로 버린다. 정교하게 만들지 않는다.

```text
모든 메서드는 자기 트랜잭션이고, 시작할 때 주문별 fake_payments 행을 배타 잠금한다
  INSERT INTO fake_payments(...) ON DUPLICATE KEY UPDATE payment_id = payment_id
  → startAttempt · resolve · recordResult가 같은 주문에서는 한 줄로 선다 (승인·만료 경합을 막는 최소 장치)

startAttempt   위 계약 1~3. 새 시도는 result = READY
resolve        계약 4·5. 기한 뒤면 READY를 FAILED로 닫고 finalDecision = true
recordResult(attemptId, result, approvedAt, approvedAmount)   ← 테스트 전용
               READY·UNKNOWN 시도만 바꿀 수 있다 (READY → APPROVED/FAILED/UNKNOWN, UNKNOWN → APPROVED/FAILED)
               FAILED로 닫힌 시도는 거절 (만료 판정 뒤 승인 방지)
deliver(attemptId)                                            ← 테스트 전용, 트랜잭션 밖
               커밋된 결과를 읽어 다른 빈의 PaymentResultHandler 호출. 실패하면 다시 부르면 된다
```

- `recordResult`(기록)와 `deliver`(전달)를 나눈다. 가짜 테이블 잠금을 쥔 채 팬미팅을 잠그지 않기 위해서다.
- `deliver`를 빼먹어도 만료 Worker의 `resolve`가 결과를 읽어 반영한다.
- 계약 위반(만료 뒤 승인, C49)은 테스트에서 가짜 결과 반영기를 거치지 않고 `PaymentResultHandler`를 직접 호출해 만든다.
- 테이블과 마이그레이션 분리는 [data-model.md](data-model.md)에 있다.

## 4. 가짜 Member Adapter

기본 전원 활성. 테스트에서 탈퇴 회원 ID, 가입 시각, 조회 예외를 지정한다.
