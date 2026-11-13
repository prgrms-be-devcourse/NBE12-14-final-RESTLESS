# 팬미팅 API

- 담당: 홍승연
- 출처: 담당자 P0 구현 명세 8장, 6.6절. 처리 흐름은 [domains/fanmeeting/transactions.md](../../domains/fanmeeting/transactions.md)
- 상태: P0 설계 동결 (2026-11-10)

| Method | Endpoint | 설명 | 권한 |
|---|---|---|---|
| POST | `/api/organizer/fan-meetings` | Event 생성. 만든 회원이 주최자 | ORGANIZER, ADMIN |
| GET | `/api/fan-meetings/{eventId}` | 상세. 게릴라 오픈이면 OPEN 전 `entryOpensAt` null. `entryClosesAt`은 OPEN 중에만 | 공개 |
| POST | `/api/fan-meetings/{eventId}/applications` | 응모·재응모 | 회원 |
| DELETE | `/api/fan-meetings/{eventId}/applications/me` | 응모 취소 | 회원 |
| GET | `/api/fan-meetings/{eventId}/applications/me` | 내 응모 상태 | 회원 |
| GET | `/api/fan-meetings/{eventId}/result/me` | 내 당첨·예비 결과 + `paymentDeadline` | 회원 |
| GET | `/api/fan-meetings/{eventId}/orders/me` | 내 주문·결제 상태 + `paymentDeadline` | 회원 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/decline` | 결제 포기 | 회원 |
| GET | `/api/admin/fan-meetings/{eventId}/summary` | 상태, 응모 수, Selection·Order 상태별 인원, 정원 점유, 승격된 예비 최대 순번, `promotion_closes_at`, 보류 목록(경과 시간), `REFUND_PENDING` 목록, 계약 위반 승인 목록 | ADMIN |

주최자 API(`/api/organizer/**`)는 `role = ORGANIZER` 또는 `ADMIN`, 관리자 API(`/api/admin/**`)는 `role = ADMIN`만 호출할 수 있다. 권한이 없으면 403 `FORBIDDEN`, 로그인하지 않았으면 401 `UNAUTHORIZED`([공통 API](common.md)).

응모는 `UNIQUE(event_id, user_id)`로 중복을 막으므로 `Idempotency-Key`를 쓰지 않는다.

## 행사 생성 `POST /api/organizer/fan-meetings`

요청(시각은 ISO-8601 UTC, 예 `2026-11-20T12:00:00Z`). `promotionClosesAt`만 선택이고 나머지는 필수다.

```json
{
  "title": "연말 팬미팅",
  "entryOpensAt": "2026-11-20T12:00:00Z",
  "firstNoticeScheduledAt": "2026-11-17T12:00:00Z",
  "winnerLimit": 300,
  "reserveLimit": 100,
  "feeAmount": 50000,
  "meetingAt": "2026-11-23T09:00:00Z",
  "promotionClosesAt": null,
  "opensAtPublic": true
}
```

- 201 `{"eventId": 1}`
- 필수 값 누락·형식 오류: 400 `INVALID_REQUEST`
- 생성 규칙 위반([policy.md 3절](../../domains/fanmeeting/policy.md)): 400 `INVALID_FAN_MEETING_SETTINGS`, `message`에 어긴 규칙

## 행사 상세 `GET /api/fan-meetings/{eventId}`

```json
{
  "eventId": 1, "title": "연말 팬미팅", "status": "SCHEDULED",
  "entryOpensAt": null, "entryClosesAt": null,
  "meetingAt": "2026-11-23T09:00:00Z", "winnerLimit": 300, "reserveLimit": 100, "feeAmount": 50000
}
```

필드는 항상 있고, 공개하지 않는 시각은 null이다.

| 필드 | 공개 조건 |
|---|---|
| `entryOpensAt` | `opens_at_public = true`(예고 오픈)이거나 상태가 `SCHEDULED`가 아님 |
| `entryClosesAt` | 상태가 `OPEN` |

## 응모·취소·내 응모

| 요청 | 성공 | 본문 |
|---|---|---|
| `POST .../applications` (첫 응모, 재응모) | 201 | `{"status": "APPLIED", "appliedAt": "..."}` |
| `DELETE .../applications/me` | 204 | 없음 |
| `GET .../applications/me` | 200 | `{"status": "APPLIED" \| "WITHDRAWN" \| "NONE", "appliedAt": ... }`. 응모한 적 없으면 `NONE`, `appliedAt` null |

- 응모·취소는 `OPEN`이고 `entry_opens_at <= now < entry_closes_at`일 때만. 아니면 409 `APPLICATION_WINDOW_CLOSED`.
- 행사가 없으면 세 요청 모두 404 `FAN_MEETING_NOT_FOUND`.
- 응모가 동시에 몰려 서버가 3번 다시 시도해도 처리하지 못하면 503 `APPLICATION_BUSY`. 같은 회원의 중복 요청은 재시도 뒤 409 `ALREADY_APPLIED`가 된다. 클라이언트는 503이면 잠시 뒤 다시 보내고, 409면 `GET .../applications/me`로 상태를 확인한다.

## 결제 (공통 결제 API)

팬미팅은 결제 API를 따로 두지 않는다. 손님은 공통 결제 API를 쓴다([공통 API](common.md)). 처리 흐름은 [transactions.md 6·7절](../../domains/fanmeeting/transactions.md#6-결제-시작-공통-결제-api).

| 단계 | 호출 | 팬미팅 값 |
|---|---|---|
| 결제 준비 | `POST /api/payments` + 헤더 `Idempotency-Key` | `payableType = FAN_MEETING`, `payableId = orderId` |
| 승인 | `POST /api/payments/confirm` | 결제창 결과(`paymentKey`, `orderId` = 결제 모듈의 `pgOrderId`, `amount`) |
| 결제 상태 | `GET /api/payments/{paymentId}` | — |

- 금액은 주문 금액(참가비), 결제 기한은 `발급 + 60분`이다. 요청 본문의 금액·기한은 쓰지 않는다.
- 결제 대기(`PENDING_PAYMENT`) 주문이 아니거나 행사가 결제 진행 중(`PAYMENT_IN_PROGRESS`)이 아니면 409 `PAYMENT_NOT_PAYABLE`, 다른 회원 주문이면 404 `PAYMENT_TARGET_NOT_FOUND`, 기한이 지났으면 409 `PAYMENT_WINDOW_EXPIRED`다(공통 결제 오류 코드).
- 시도가 실패하면 **새 키**로 다시 결제를 준비한다.

## 오류 코드

| 상황 | HTTP | 코드 |
|---|---:|---|
| 행사 없음 | 404 | `FAN_MEETING_NOT_FOUND` |
| 생성 값 위반 | 400 | `INVALID_FAN_MEETING_SETTINGS` |
| 로그인 안 함 / 토큰 만료 | 401 | `UNAUTHORIZED` (팀 공통) |
| 관리자 권한 없음 | 403 | `FORBIDDEN` (팀 공통) |
| 응모 시간 아님·마감 | 409 | `APPLICATION_WINDOW_CLOSED` |
| 이미 응모 | 409 | `ALREADY_APPLIED` |
| 가입 기준 시각 이후 가입 | 403 | `APPLICATION_NOT_ELIGIBLE` |
| 가입 시각 조회 실패 | 503 | `MEMBER_LOOKUP_FAILED` |
| 응모 재시도 모두 실패 | 503 | `APPLICATION_BUSY` |
| 취소할 응모 없음 | 409 | `APPLICATION_NOT_ACTIVE` |
| 주문 없음·다른 행사·다른 회원 | 404 | `ORDER_NOT_FOUND` |
| 결제 가능 상태 아님 | 409 | `ORDER_NOT_PAYABLE` |
| 결제 기한 지난 주문의 포기 | 409 | `PAYMENT_WINDOW_EXPIRED` |
| 결제를 준비한 주문의 포기 | 409 | `DECLINE_NOT_ALLOWED` |

결제 준비·승인 오류는 공통 결제 오류 코드를 쓴다([공통 API](common.md)).

오류 코드 일부(`APPLICATION_*`, `ORDER_*`, `PAYMENT_*`)는 [공통 규칙 4번](README.md#공통-규칙-proposed)의 `도메인_상황` 형식과 달리 도메인 접두어가 없다. 명세 동결로 이름을 그대로 둔다.

## DTO 원칙

- 엔티티를 그대로 응답하지 않는다.
- `random_seed`, `lease_token`, 다른 회원 정보는 응답에 넣지 않는다.
- 팀 공통 `ErrorResponse` 형식([README.md](README.md))을 따른다.
- 회원 ID는 요청 본문이 아니라 인증 정보에서 얻는다.
- 다른 회원의 주문은 403이 아니라 404 `ORDER_NOT_FOUND`로 답한다.
