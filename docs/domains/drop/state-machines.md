# Drop 상태 머신

> 관련 코드: `backend/src/main/java/com/restless/drop/domain/`, `backend/src/main/java/com/restless/order/domain/`, `backend/src/main/java/com/restless/payment/domain/`
>
> 마지막 확인: 2026-11-22 (#330 `DropReservation`에 재고 버킷 번호가 생겼다. 상태와 전이는 바뀌지 않았다. 2026-11-20 #285 용어 정리, 2절은 #254에서 구현됐다. 2026-11-19 #253 SKU별 입장 제어 정책 반영. 나머지는 2026-11-14 #92 구현과 대조)

Order, Payment, Reservation 상태는 따로 저장하고, 조합 간 정합성을 검증한다.

## 1. Drop 판매 상태

```text
DRAFT → SCHEDULED → OPEN → CLOSED
DRAFT / SCHEDULED → CANCELLED
```

판매 가능 여부는 게시된 상태(`SCHEDULED` 또는 `OPEN`)와 서버 시각 조건(`opensAt <= now < closesAt`)으로 판정한다. `OPEN` 전환은 판정에 영향이 없고, 판매 가능 상태가 아니면(`CLOSED`, `CANCELLED` 등) 시각과 관계없이 판매하지 않는다 (#92) ([API 계약 D2](../../contracts/api/drop.md), 2026-11-13).

현재 구현된 전환은 게시(`DRAFT → SCHEDULED`) 하나다. `OPEN`, `CLOSED`, `CANCELLED`로 바꾸는 기능은 아직 없다. 응답의 `saleStatus`는 판매 판정과 같은 기준(`Drop.saleStatus`)으로 계산한다. 판매 가능 상태가 아니면 `ENDED`, 그 밖에는 서버 시각으로 정한다 (#92).

## 2. 입장 신청 상태 (SKU별, #254 구현)

입장 신청 행은 `(drop_id, variant_id, user_id)`에 대한 것이고, 입장 순번과 입장은 `variant_id`(SKU)별로 독립이다. 회원이 같은 드롭에서 가진 살아 있는 대기는 최대 1건이다. 다른 SKU로 진입하면 기존 대기를 `CLOSED`로 빼고 새 SKU의 `WAITING`을 만든다.

```text
NONE → WAITING → ADMITTED → CONSUMED
WAITING → CLOSED
ADMITTED → CLOSED
ADMITTED → EXPIRED
WAITING → UNAVAILABLE
ADMITTED → UNAVAILABLE
```

| 전이 | 조건 |
|---|---|
| NONE → WAITING | 판매 중이고 그 SKU의 `available > 0`일 때 SKU를 골라 진입. `variant_id` 저장. `available = 0`이면 진입을 거절하고 행을 만들지 않는다 |
| WAITING → ADMITTED | 같은 SKU의 추가 허가 가능 수 `max(0, available - 미사용 유효 ADMITTED 수)`가 1 이상이고 배치 제한 안일 때, 같은 SKU의 입장 순번(`id`)대로 |
| ADMITTED → CONSUMED | 그 SKU의 재고 점유 성공과 같은 트랜잭션 (TX1) |
| ADMITTED → EXPIRED | `now >= admitted_until` |
| WAITING → CLOSED | 마지막 상태 조회가 30초보다 오래 끊김(페이지를 나감), 판매 기간 종료, 또는 같은 회원이 같은 드롭의 다른 SKU로 진입 |
| ADMITTED → CLOSED | 같은 회원이 같은 드롭의 다른 SKU로 진입. 미사용 입장 허가를 포기한 것이다 |
| WAITING 또는 ADMITTED → UNAVAILABLE | 그 SKU의 `available = 0`을 입장 허가 작업이 관찰함. `reserved` 수량과 무관. 다른 SKU는 영향 없음 |

`UNAVAILABLE`은 2026-11-19에 정한 종료 상태 이름이다. 검토한 후보(`CLOSED` 재사용, `EXPIRED` 재사용, `SOLD_OUT`)는 [policy.md 7-1절](policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)에 있다.

끝난 상태의 원인은 서로 다르다: `CONSUMED` 사용 완료(구매 성공), `EXPIRED` 허가 만료(입장 허가 유효시간 `admitted_until` 경과), `CLOSED` 대기 종료(입장 대기 중 이탈 또는 판매 기간 종료), `UNAVAILABLE` 구매 불가 종료(그 SKU의 `available = 0`).

- `UNAVAILABLE`, `CONSUMED`, `EXPIRED`, `CLOSED`는 끝난 상태다. 다시 `WAITING`이나 `ADMITTED`가 되지 않는다. 재고가 반환돼 `available > 0`이 되어도 종료된 입장 순번과 입장 허가는 복구하지 않고, 사용자는 새 `WAITING` 행으로 다시 진입한다.
- 종료는 상태 전이다. 행을 지우지 않고 thread를 끝내지 않는다. 이미 `CONSUMED`인 행과 그 점유는 종료 대상이 아니고 취소되지 않는다.
- `ADMITTED`는 구매를 시도할 권한이며 재고 확보를 뜻하지 않는다. `UNAVAILABLE`이 된 입장 허가로는 신규 점유를 만들 수 없다 (INV-06).
- 현재 구매 불가(`available = 0`)는 최종 매진이 아니다. 입장 신청 행에 별도 "품절" 상태를 두지 않는다.
- 입장 대기(`WAITING`), 입장 허가(`ADMITTED`), DB row lock 대기는 서로 다르다 ([policy.md 7-1절](policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)).

## 3. 재고 점유 상태

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
| UNKNOWN → FAILED | PG 재조회가 최종 NotApproved이거나, 승인 시작 후 15분 이상 지난 명시적 NotFound일 때만 쓴다. Unknown/통신 오류/처리 중은 실패로 추측하지 않는다 ([재확인 규칙](../../operations/failure-handling.md#미완료-결제-재확인-작업-46)) |
| CANCEL_PENDING → APPROVED | PG가 취소를 거절했다고 확정 응답한 경우. 결제는 승인 상태로 돌아간다 |
| CANCEL_UNKNOWN → APPROVED | 같은 멱등성 키의 취소 재시도가 명시적 Rejected를 돌려준 경우. 조회 Approved만으로 되돌리지 않고 동일 취소를 재시도한다 |

- UNKNOWN은 FAILED가 아니다.
- 상태를 바꾸는 트랜잭션은 결제 행을 `SELECT ... FOR UPDATE`로 먼저 잠근 뒤 전이한다. 같은 결제의 동시 승인, 중복 결과 처리에서 한쪽만 반영된다.
- FAILED, CANCELLED가 아닌 결제는 결제 대상(`payable_type`, `payable_id`)마다 최대 1건이다 (DB UNIQUE).
