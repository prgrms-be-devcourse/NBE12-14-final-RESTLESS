# Drop 상태 머신

Order, Payment, Reservation 상태는 따로 저장하고, 조합 간 정합성을 검증한다.

## 1. Drop 판매 상태

```text
DRAFT → SCHEDULED → OPEN → CLOSED
DRAFT / SCHEDULED → CANCELLED
```

판매 가능 여부는 `OPEN` 상태와 서버 시각 조건을 함께 검증한다.

## 2. 대기열 상태

```text
NONE → WAITING → ADMITTED → CONSUMED
WAITING → CLOSED
ADMITTED → EXPIRED / CLOSED
```

`ADMITTED`는 구매를 시도할 권한이며 재고 확보를 뜻하지 않는다.

## 3. 재고 예약 상태

```text
RESERVED
  ├─→ CONFIRMING
  │     ├─→ CONFIRMED
  │     │     └─→ RESTORED
  │     ├─→ RELEASED
  │     └─→ RECONCILING
  │             ├─→ CONFIRMED
  │             ├─→ RELEASED
  │             └─→ COMPENSATING
  │                     └─→ RELEASED
  ├─→ EXPIRED
  └─→ RELEASED
```

| 상태 | 의미 |
|---|---|
| RESERVED | 재고 확보, 주문 생성 완료 |
| CONFIRMING | PG 최종 승인 시작 |
| RECONCILING | 승인 결과 조회, 복구 중 |
| COMPENSATING | 승인된 PG 거래의 보상 취소 중 |
| CONFIRMED | 구매 확정 |
| EXPIRED | 승인 시작 전 예약 만료 |
| RELEASED | 재고 반환 완료 |
| RESTORED | 구매 후 전액 환불로 재고 복원 |

## 4. 공통 Order 상태 (공통 모듈 소유, 팀 계약 필요)

```text
PENDING_PAYMENT → PAYMENT_CONFIRMING
PAYMENT_CONFIRMING → PAID / PAYMENT_REVIEW / CANCELLED
PAYMENT_REVIEW → PAID / CANCELLED
PAID → REFUND_PENDING → REFUNDED / REFUND_REVIEW
REFUND_REVIEW → REFUNDED / PAID
PENDING_PAYMENT → CANCELLED
```

## 5. 공통 Payment 상태 (공통 모듈 소유, 팀 계약 필요)

```text
READY → PROCESSING → APPROVED / FAILED / UNKNOWN
UNKNOWN → APPROVED / FAILED
APPROVED → CANCEL_PENDING → CANCELLED / CANCEL_UNKNOWN
CANCEL_UNKNOWN → CANCELLED / APPROVED
```

UNKNOWN은 FAILED가 아니다.
