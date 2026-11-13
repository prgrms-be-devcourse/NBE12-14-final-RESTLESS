# FanMeeting 상태 머신

Event, Application, Selection, Order 상태를 따로 저장하고, Selection–Order 조합의 정합성을 검증한다.

## 1. Event

```text
SCHEDULED → OPEN → CLOSED → DRAWING → PAYMENT_IN_PROGRESS → RECRUITMENT_COMPLETED
DRAWING → RECRUITMENT_COMPLETED   (추첨 후보 0명)
```

- 생성하면 바로 `SCHEDULED`.
- Open Worker가 늦어 `entry_closes_at`까지 지났으면 `OPEN`하지 않고 `SCHEDULED`로 둔다(`FANMEETING_OPEN_MISSED` 로그).

## 2. Application

```text
(없음) → APPLIED → WITHDRAWN → APPLIED (재응모, 같은 행)
```

재응모 때 `applied_at`을 현재 시각으로 갱신한다. 취소 때는 `applied_at`을 유지한다.

## 3. Selection

| 현재 | 조건 | 다음 |
|---|---|---|
| (생성) | 추첨 최종화, WINNER | `PAYMENT_PENDING` |
| (생성) | 추첨 최종화, RESERVE | `WAITING` |
| `WAITING` | 승격 | `PAYMENT_PENDING` |
| `WAITING` | 승격 차례에 탈퇴 확인 | `NOT_NEEDED` |
| `WAITING` | 모집 완료 시 남은 예비 | `NOT_NEEDED` |
| `PAYMENT_PENDING` | 기한 내 승인 | `CONFIRMED` |
| `PAYMENT_PENDING` | 늦은 승인·금액 불일치 | `EXPIRED` |
| `PAYMENT_PENDING` | 기한 경과 + 최종 미승인 | `EXPIRED` |
| `PAYMENT_PENDING` | 결제 시도 요청 전 포기 | `DECLINED` |

보류 중에는 `PAYMENT_PENDING`을 유지한다(자리를 차지).

P0에서 끝 상태(더 바뀌지 않음): `CONFIRMED`, `EXPIRED`, `DECLINED`, `NOT_NEEDED`.

## 4. Order

| 현재 | 조건 | 다음 | Selection | `approved_attempt_id` |
|---|---|---|---|---|
| (생성) | 추첨 최종화(WINNER) 또는 승격 | `PENDING_PAYMENT` | `PAYMENT_PENDING` | NULL |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도 승인, 금액 일치, `approvedAt <= 기한` | `PAID` | `CONFIRMED` | 그 시도 |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도 승인, `approvedAt > 기한` | `REFUND_PENDING` (거절 → 보상 취소) | `EXPIRED` | 그 시도 |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도 승인, 금액 불일치 | `REFUND_PENDING` (거절 → 보상 취소) | `EXPIRED` | 그 시도 |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도가 아닌 승인 | 유지 (거절 → 보상 취소) | 유지 | — |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도 실패, 기한 남음 | `PENDING_PAYMENT` | 유지 | — |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 현재 시도 실패, 기한 지남 | `EXPIRED` | `EXPIRED` | — |
| `PENDING_PAYMENT` | 승인 시작 전에 시도가 닫힘(결제창만 열고 기한 지남) | `EXPIRED` | `EXPIRED` | — |
| `PENDING_PAYMENT` | 결과 모름(UNKNOWN) | `PAYMENT_REVIEW` | 유지 | — |
| `PENDING_PAYMENT` | 기한 경과 + 진행 중인 시도 없음 (만료 Worker) | `EXPIRED` | `EXPIRED` | — |
| `PENDING_PAYMENT` | 결제 준비 요청 전 포기 | `CANCELLED` | `DECLINED` | — |
| `PAYMENT_REVIEW` | 결과 모름 재도착 | 유지(아무것도 안 바꿈) | 유지 | — |
| `REFUND_PENDING` | 보상 취소 완료 | `REFUNDED` | 유지 | 유지 |
| `PAID` | 같은 시도의 승인 재도착 | 유지(받아들임) | 유지 | 유지 |
| `PAID`, `REFUND_PENDING`, `REFUNDED`, `EXPIRED`, `CANCELLED` | 그 밖의 승인 도착 (이중 결제, **만료 뒤 승인은 결제 모듈 계약 위반**) | 유지 (거절 → 보상 취소, 자리 반환 없음) | 유지 | 유지 |

- **현재 시도:** 승인 시작을 받아들인 시도(`payment_id`). 시도가 실패로 끝나면 비운다.
- **보류 시각:** 다른 상태에서 `PAYMENT_REVIEW`로 **바뀔 때만** `review_started_at = now`. 보류에서 나갈 때 `review_started_at`, `review_warned_at`을 NULL. 그래서 30분 경고는 보류 회차당 1회다.
- 거절한 승인의 환불은 결제 모듈이 보상 취소로 실제로 한다. 팬미팅은 환불 명령을 보내지 않는다.
- P0에서 끝 상태: `PAID`, `EXPIRED`, `CANCELLED`, `REFUNDED`. `REFUND_PENDING`은 보상 취소가 끝나면 `REFUNDED`가 된다.

## 5. Selection–Order 짝

이 표에 없는 조합은 버그다 (INV-FM-09).

| Selection | 가능한 Order |
|---|---|
| `WAITING`, `NOT_NEEDED` | 없음 |
| `PAYMENT_PENDING` | `PENDING_PAYMENT`, `PAYMENT_REVIEW` |
| `CONFIRMED` | `PAID` |
| `EXPIRED` | `EXPIRED`, `REFUND_PENDING`, `REFUNDED` |
| `DECLINED` | `CANCELLED` |

## 6. DrawRun

```text
PENDING → RUNNING → COMPLETED
RUNNING (lease_until < now) → RUNNING (다른 Worker가 새 lease_token으로 Claim)
```
