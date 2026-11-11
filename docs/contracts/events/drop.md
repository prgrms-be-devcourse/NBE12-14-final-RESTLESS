# Drop 이벤트 계약 (후보)

- 상태: PROPOSED. 이벤트 소유권은 공통 Order와 Drop이 협의한다.
- 처리 방식: [ADR-0001](../../decisions/0001-event-handling.md)

## 후보

| 이벤트 | 발행 시점 |
|---|---|
| `OrderPaidEvent` 또는 `DropPurchaseConfirmedEvent` | TX3 커밋 후 |
| `OrderRefundedEvent` 또는 `DropPurchaseRefundedEvent` | TX5 커밋 후 |

## 후속 작업 후보

어떤 작업을 실제로 구현할지는 팀 확인이 필요하다.

| 작업 | 원본 데이터로 복구 가능 | 처리 |
|---|---|---|
| 구매 완료 알림 | 불가 | AFTER_COMMIT 리스너, 알림 테이블에 저장 |
| 판매 통계 | 가능 | 이벤트로 누적하지 않고 주문 테이블에서 계산 |
| 상품 인기도 | 가능 | 이벤트로 누적하지 않고 주문 테이블에서 계산 |

핵심 거래 상태는 이벤트 리스너에 맡기지 않는다.
