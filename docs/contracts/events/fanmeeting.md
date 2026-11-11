# Fan Meeting 이벤트 계약 (초안)

- 상태: PROPOSED. 이벤트 전달 방식은 팀 협의 후 결정한다.
- 처리 방식: [ADR-0001](../../decisions/0001-event-handling.md)
- P0는 가짜 결제 Adapter로 개발하고, 공통 결제 모듈이 준비되면 Adapter만 바꾼다.

```mermaid
flowchart LR
    F["팬미팅"] -->|결제 요청 · 결과 조회| P["공통 결제"]
    P -->|결제·환불 결과| F
    F -->|당첨·확정·취소 (P2)| N["알림·참여 내역"]
```

## 1. 수신 (공통 결제 → 팬미팅)

| 이벤트 | 팬미팅 처리 |
|---|---|
| `PaymentSucceeded` | `approvedAt`으로 기한 판정. 기한 안이면 참가 확정, 늦으면 환불 대상 |
| `PaymentFailed` | 이번 시도만 실패. 기한이 남으면 재시도 허용 |
| `PaymentOutcomeUnknown` | 보류, 자리 유지 |
| `PaymentResolutionUpdated` | 모든 시도가 정리된 한 주문의 최종 결과 |
| `RefundSucceeded` | 환불 완료 |
| `RefundFailed` | 재시도 대기 |

- 같은 이벤트를 여러 번 받아도 결과가 같아야 한다 (멱등성).
- 주문과 선발 상태는 같은 트랜잭션에서 바꾼다.

## 2. 발행 (P2)

| 이벤트 | 발생 시점 | 활용 |
|---|---|---|
| `FanMeetingSelectionNotified` | 당첨·승격·잔여석 구매 | 알림 |
| `FanMeetingParticipationConfirmed` | 참가 확정 | 알림, 참여 내역 |
| `FanMeetingParticipationCancelled` | 포기·만료·환불 | 참여 내역 |

## 3. 결제 모듈 직접 호출

결제 결과 조회는 이벤트가 아니라 직접 호출한다.

```text
PaymentResolutionResult resolve(payableType = "FAN_MEETING_ORDER", payableId)
  → NOT_STARTED | APPROVED | NOT_APPROVED_FINAL | PROCESSING | UNKNOWN
    + approvedAt, attemptId, approvedAmount
```
