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
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 승인, 금액 일치, `approvedAt <= 기한` | `PAID` | `CONFIRMED` | 그 시도 |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 승인, `approvedAt > 기한` | `REFUND_PENDING` | `EXPIRED` | 그 시도 |
| `PENDING_PAYMENT` / `PAYMENT_REVIEW` | 승인, 금액 불일치 | `REFUND_PENDING` | `EXPIRED` | 그 시도 |
| `PENDING_PAYMENT` | 시도 실패, 기한 남음 | 유지 | 유지 | — |
| `PENDING_PAYMENT` | 결과 UNKNOWN/PROCESSING | `PAYMENT_REVIEW` | 유지 | — |
| `PENDING_PAYMENT` | 기한 경과 + `NOT_STARTED`/`NOT_APPROVED_FINAL` | `EXPIRED` | `EXPIRED` | — |
| `PENDING_PAYMENT` | 기한 경과 + `PROCESSING`/`UNKNOWN` | `PAYMENT_REVIEW` | 유지 | — |
| `PENDING_PAYMENT` | 결제 시도 요청 전 포기 | `CANCELLED` | `DECLINED` | — |
| `PAYMENT_REVIEW` | UNKNOWN/PROCESSING 재도착 | 유지(아무것도 안 바꿈) | 유지 | — |
| `PAYMENT_REVIEW` | 최종 미승인, 기한 남음 | `PENDING_PAYMENT` | 유지 | — |
| `PAYMENT_REVIEW` | 최종 미승인, 기한 지남 | `EXPIRED` | `EXPIRED` | — |
| `EXPIRED` | 승인 도착 (**결제 모듈 계약 위반**) | `REFUND_PENDING` | `EXPIRED` 유지(자리 반환 없음) | 그 시도 |
| `PAID`, `REFUND_PENDING` | 승인 도착, 시도 ID = `approved_attempt_id` | 유지(무시) | 유지 | 유지 |
| `PAID`, `REFUND_PENDING` | 승인 도착, 다른 시도 ID (이중 결제) | 유지 + 환불 대상 기록 | 유지 | 유지 |
| `CANCELLED` | 승인 도착 | 유지 + 오류·환불 대상 기록 | 유지 | 유지 |

- **보류 시각:** 다른 상태에서 `PAYMENT_REVIEW`로 **바뀔 때만** `review_started_at = now`. 보류에서 나갈 때 `review_started_at`, `review_warned_at`을 NULL. 그래서 30분 경고는 보류 회차당 1회다.
- 환불은 표시만 한다(`REFUND_PENDING`). 실제 환불 명령은 보내지 않는다.
- P0에서 끝 상태: `PAID`, `EXPIRED`, `CANCELLED`, `REFUND_PENDING`(P1에서 `REFUNDED`로 이어짐). 끝 상태에 결과가 와도 위 표의 마지막 네 줄만 적용한다.

## 5. Selection–Order 짝

이 표에 없는 조합은 버그다 (INV-FM-09).

| Selection | 가능한 Order |
|---|---|
| `WAITING`, `NOT_NEEDED` | 없음 |
| `PAYMENT_PENDING` | `PENDING_PAYMENT`, `PAYMENT_REVIEW` |
| `CONFIRMED` | `PAID` |
| `EXPIRED` | `EXPIRED`, `REFUND_PENDING` |
| `DECLINED` | `CANCELLED` |

## 6. DrawRun

```text
PENDING → RUNNING → COMPLETED
RUNNING (lease_until < now) → RUNNING (다른 Worker가 새 lease_token으로 Claim)
```
