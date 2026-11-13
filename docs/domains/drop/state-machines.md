# Drop 상태 머신

> 관련 코드: `backend/src/main/java/com/restless/drop/domain/`, `backend/src/main/java/com/restless/order/domain/`, `backend/src/main/java/com/restless/payment/domain/`
>
> 마지막 확인: 2026-11-13 (#79에서 코드와 대조)

Order, Payment, Reservation 상태는 따로 저장하고, 조합 간 정합성을 검증한다.

## 1. Drop 판매 상태

```text
DRAFT → SCHEDULED → OPEN → CLOSED
DRAFT / SCHEDULED → CANCELLED
```

판매 가능 여부는 게시된 상태(`SCHEDULED` 또는 `OPEN`)와 서버 시각 조건(`opensAt <= now < closesAt`)으로 판정한다. `OPEN`, `CLOSED` 전환은 표시용이며 판매 판정에 쓰지 않는다 ([API 계약 D2](../../contracts/api/drop.md), 2026-11-13).

현재 구현된 전환은 게시(`DRAFT → SCHEDULED`) 하나다. `OPEN`, `CLOSED`, `CANCELLED`로 바꾸는 기능은 아직 없다. 응답의 `saleStatus`는 상태가 아니라 서버 시각으로 계산한다.

## 2. 대기열 상태

```text
NONE → WAITING → ADMITTED → CONSUMED
WAITING → CLOSED
ADMITTED → EXPIRED
```

`ADMITTED`는 구매를 시도할 권한이며 재고 확보를 뜻하지 않는다. `CLOSED`는 대기 중 이탈(상태 조회 끊김)이나 판매 종료로 닫힌 대기다. 입장한 뒤에는 TTL로만 끝난다(`EXPIRED`).

## 3. 재고 예약 상태

```text
RESERVED
  ├─→ CONFIRMING
  │     ├─→ CONFIRMED
  │     │     └─→ RESTORED
  │     ├─→ RESERVED        (실패 확정, 예약 기한 전: 재결제 대기)
  │     ├─→ RELEASED        (실패 확정, 예약 기한 뒤)
  │     ├─→ COMPENSATING
  │     │       └─→ RELEASED
  │     └─→ RECONCILING
  │             ├─→ CONFIRMED
  │             ├─→ RESERVED  (실패 확정, 예약 기한 전)
  │             ├─→ RELEASED  (실패 확정, 예약 기한 뒤)
  │             └─→ COMPENSATING
  │                     └─→ RELEASED
  ├─→ EXPIRED
  └─→ RELEASED
```

| 전이 | 조건 |
|---|---|
| CONFIRMING/RECONCILING → RESERVED | 현재 시도(`current_payment_id`)의 실패가 확정되고, 확정 시각이 예약 기한 전. 재고와 구매 슬롯 유지 |
| CONFIRMING/RECONCILING → RELEASED | 현재 시도의 실패가 기한과 같거나 뒤에 확정됨. 재고 반환 |
| CONFIRMING/RECONCILING → COMPENSATING | 현재 시도의 승인이 왔지만 Handler가 거절함 (금액 불일치). 결제 모듈이 보상 취소. 현재 시도가 아닌 승인은 예약을 바꾸지 않고 거절만 한다 (INV-15) |
| COMPENSATING → RELEASED | 보상 취소 완료 (`onRefunded`, `COMPENSATION`) |
| CONFIRMED → RESTORED | 손님 환불 완료 (`onRefunded`, `CUSTOMER_REFUND`) |
| RESERVED → EXPIRED | 기한이 지날 때까지 승인을 시작하지 않음. 재결제 대기 중인 예약도 포함 |

CONFIRMING, RECONCILING, COMPENSATING은 일반 만료 작업이 반환하지 않는다. 기한 안에 승인 시작을 받아들인 시도는 승인이 늦게 확인되어도 확정한다.

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
PAYMENT_CONFIRMING → PAID / PAYMENT_REVIEW / CANCELLED / PENDING_PAYMENT
PAYMENT_REVIEW → PAID / CANCELLED / PENDING_PAYMENT
PAID → REFUND_PENDING → REFUNDED / REFUND_REVIEW
REFUND_REVIEW → REFUNDED / PAID
PENDING_PAYMENT → CANCELLED
```

`PAYMENT_CONFIRMING`/`PAYMENT_REVIEW` → `PENDING_PAYMENT`: 이번 결제 시도의 실패가 확정됐지만 결제 대상의 기한이 남아 다시 결제할 수 있다 (2026-11-12 추가).
기한이 지났으면 `CANCELLED`로 간다. 주문을 다시 결제 대기로 돌릴지는 결제 대상 도메인의 정책이 정한다.

## 5. 공통 Payment 상태 (공통 결제 모듈 소유, [ADR-0003](../../decisions/0003-payment-module-boundary.md))

```text
READY → PROCESSING → APPROVED / FAILED / UNKNOWN
READY → FAILED
UNKNOWN → APPROVED / FAILED
APPROVED → CANCEL_PENDING → CANCELLED / CANCEL_UNKNOWN / APPROVED
CANCEL_UNKNOWN → CANCELLED / APPROVED
```

| 전이 | 쓰는 경우 |
|---|---|
| READY → FAILED | 승인을 시작하지 않은 시도를 닫는다. 닫힌 시도는 승인되지 않는다. 사유: 기한(`expires_at`) 경과(`EXPIRED`), 같은 대상의 새 키 결제 준비로 교체(`SUPERSEDED`), Handler의 승인 시작 거절(`APPROVAL_REJECTED`) |
| UNKNOWN → FAILED | PG 재조회가 최종 NotApproved이거나, 승인 시작 후 15분 이상 지난 명시적 NotFound일 때만 쓴다. Unknown/통신 오류/처리 중은 실패로 추측하지 않는다 ([대사 규칙](../../operations/failure-handling.md#공통-결제-대사-46)) |
| CANCEL_PENDING → APPROVED | PG가 취소를 거절했다고 확정 응답한 경우. 결제는 승인 상태로 돌아간다 |
| CANCEL_UNKNOWN → APPROVED | 같은 멱등성 키의 취소 재시도가 명시적 Rejected를 돌려준 경우. 조회 Approved만으로 되돌리지 않고 동일 취소를 재시도한다 |

- UNKNOWN은 FAILED가 아니다.
- 상태를 바꾸는 트랜잭션은 결제 행을 `SELECT ... FOR UPDATE`로 먼저 잠근 뒤 전이한다. 같은 결제의 동시 승인, 중복 결과 처리에서 한쪽만 반영된다.
- FAILED, CANCELLED가 아닌 결제는 결제 대상(`payable_type`, `payable_id`)마다 최대 1건이다 (DB UNIQUE).
