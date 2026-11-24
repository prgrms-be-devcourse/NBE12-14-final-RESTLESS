# 팬미팅 API

> 담당: 홍승연
>
> 상태: IMPLEMENTED (P0 설계 동결 2026-11-10, 코드 기준)
>
> 관련 코드: `backend/src/main/java/com/restless/fanmeeting/api/`
>
> 마지막 확인: 2026-11-17 (엔드포인트와 상세 응답 필드를 컨트롤러·DTO와 대조)

- 출처: 담당자 P0 구현 명세 8장, 6.6절. 처리 흐름은 [domains/fanmeeting/transactions.md](../../domains/fanmeeting/transactions.md)

| Method | Endpoint | 설명 | 권한 | 구현 |
|---|---|---|---|---|
| GET | `/api/fan-meetings` | 최신 ID순 공개 목록, 페이지·단일 status 필터 | 공개 | 구현 |
| PUT | `/api/organizer/fan-meetings/{eventId}` | 생성과 같은 전체 본문으로 오픈 전 수정 | 본인 ORGANIZER, ADMIN | 구현 |
| POST | `/api/organizer/fan-meetings/{eventId}/cancel` | 오픈 전 멱등 취소 | 본인 ORGANIZER, ADMIN | 구현 |
| POST | `/api/organizer/fan-meetings` | Event 생성. 만든 회원이 주최자 | ORGANIZER, ADMIN | 구현 |
| GET | `/api/fan-meetings/{eventId}` | 상세. 게릴라 오픈이면 OPEN 전 `entryOpensAt` null. `entryClosesAt`은 OPEN 중에만 | 공개 | 구현 |
| POST | `/api/fan-meetings/{eventId}/applications` | 응모·재응모 | 회원 | 구현 |
| DELETE | `/api/fan-meetings/{eventId}/applications/me` | 응모 취소 | 회원 | 구현 |
| GET | `/api/fan-meetings/{eventId}/applications/me` | 내 응모 상태 | 회원 | 구현 |
| GET | `/api/fan-meetings/{eventId}/result/me` | 내 당첨·예비 결과 + `paymentDeadline` | 회원 | 구현 |
| GET | `/api/fan-meetings/{eventId}/orders/me` | 내 주문·결제 상태 + `paymentDeadline` | 회원 | 구현 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/decline` | 결제 포기 | 회원 | 구현 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/confirm` | 0원 주문 확정(결제 없음) | 회원 | 구현 |
| GET | `/api/admin/fan-meetings/{eventId}/summary` | 상태, 응모 수, Selection·Order 상태별 인원, 정원 점유, 승격된 예비 최대 순번, `promotionClosesAt`, 보류 목록(경과 시간), `REFUND_PENDING` 목록 | ADMIN | 구현 |

주최자 API(`/api/organizer/**`)는 `role = ORGANIZER` 또는 `ADMIN`, 관리자 API(`/api/admin/**`)는 `role = ADMIN`만 호출할 수 있다. 권한이 없으면 403 `FORBIDDEN`, 로그인하지 않았으면 401 `UNAUTHORIZED`([인증 규칙](auth.md#인증-규칙)).

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
- 같은 회원이 1초에 10회를 넘겨 응모하면 429 `TOO_MANY_REQUESTS` ([요청 횟수 제한](README.md#요청-횟수-제한)).
- 응모가 동시에 몰려 서버가 3번 다시 시도해도 처리하지 못하면 503 `APPLICATION_BUSY`. 같은 회원의 중복 요청은 재시도 뒤 409 `ALREADY_APPLIED`가 된다. 클라이언트는 503이면 잠시 뒤 다시 보내고, 409면 `GET .../applications/me`로 상태를 확인한다.

## 내 결과 `GET /api/fan-meetings/{eventId}/result/me`

항상 200이다. 추첨이 확정되기 전에는 `PENDING`만 내보낸다(결과가 미리 새지 않게).

```json
{
  "result": "WINNER",
  "selectionStatus": "PAYMENT_PENDING",
  "reserveRank": null,
  "orderId": 10,
  "paymentDeadline": "2026-11-20T13:10:00Z"
}
```

| `result` | 뜻 | 나머지 값 |
|---|---|---|
| `NOT_APPLIED` | 응모하지 않았거나 취소했다 | 모두 null |
| `PENDING` | 응모했고 추첨이 아직 확정되지 않았다(응모 중, 마감, 추첨 중) | 모두 null |
| `WINNER` | 당첨 | `selectionStatus`, `orderId`, `paymentDeadline` |
| `RESERVE` | 예비 | `selectionStatus`, `reserveRank`(본인 순번, 1부터). 승격되면 `orderId`, `paymentDeadline`도 |
| `NOT_SELECTED` | 응모했지만 뽑히지 않았다 | 모두 null |

- `selectionStatus`: `PAYMENT_PENDING`, `WAITING`, `CONFIRMED`, `EXPIRED`, `DECLINED`, `NOT_NEEDED`.
- 승격된 예비자도 `result`는 `RESERVE`다. `selectionStatus = PAYMENT_PENDING`과 `orderId`로 결제 차례임을 안다.
- 다른 회원의 결과와 순번은 내보내지 않는다.

## 내 주문·결제 포기·0원 확정

| 요청 | 성공 | 본문 |
|---|---|---|
| `GET .../orders/me` | 200 | 주문 |
| `POST .../orders/{orderId}/decline` | 200 | 주문(`status = CANCELLED`) |
| `POST .../orders/{orderId}/confirm` | 200 | 주문(`status = PAID`). 이미 확정한 0원 주문이면 그대로 200 |

```json
{
  "orderId": 10,
  "status": "PENDING_PAYMENT",
  "amount": 50000,
  "issuedAt": "2026-11-20T12:10:00Z",
  "paymentDeadline": "2026-11-20T13:10:00Z",
  "paidAt": null,
  "declinable": true
}
```

- `status`: `PENDING_PAYMENT`, `PAYMENT_REVIEW`, `PAID`, `EXPIRED`, `CANCELLED`, `REFUND_PENDING`, `REFUNDED`.
- `declinable`: 지금 포기할 수 있는지. 결제 대기이고 결제를 준비한 적 없고 기한 전일 때만 true다.
- 회원 1명은 행사 1개에 주문이 최대 1개다. 주문이 없으면(예비 대기, 미선발, 미응모) 404 `ORDER_NOT_FOUND`.
- `amount`가 0이면 결제 준비(`POST /api/payments`) 대신 `confirm`을 부른다. 기한 안에 부르지 않으면 만료된다.
- 포기: 결제를 한 번이라도 준비한 주문은 409 `DECLINE_NOT_ALLOWED`, 결제 대기가 아니면 409 `ORDER_NOT_PAYABLE`, 기한이 지났으면 409 `PAYMENT_WINDOW_EXPIRED`.
- 확정: 0원 주문이 아니거나 결제 대기가 아니거나 행사가 결제 진행 중이 아니면 409 `ORDER_NOT_PAYABLE`, 기한이 지났으면 409 `PAYMENT_WINDOW_EXPIRED`.
- 포기·확정에서 주문이 없거나 다른 행사·다른 회원의 주문이면 404 `ORDER_NOT_FOUND`, 행사가 없으면 404 `FAN_MEETING_NOT_FOUND`.

## 관리자 summary `GET /api/admin/fan-meetings/{eventId}/summary`

200. 없는 행사는 404 `FAN_MEETING_NOT_FOUND`, ADMIN이 아니면 403 `FORBIDDEN`.

```json
{
  "eventId": 1,
  "status": "PAYMENT_IN_PROGRESS",
  "winnerLimit": 300,
  "reserveLimit": 100,
  "promotionClosesAt": "2026-11-22T09:00:00Z",
  "applicationCount": 12000,
  "occupied": 287,
  "selectionCounts": {"PAYMENT_PENDING": 12, "WAITING": 80, "CONFIRMED": 275, "EXPIRED": 20, "DECLINED": 5, "NOT_NEEDED": 0},
  "orderCounts": {"PENDING_PAYMENT": 10, "PAYMENT_REVIEW": 2, "PAID": 275, "EXPIRED": 18, "CANCELLED": 5, "REFUND_PENDING": 1, "REFUNDED": 1},
  "maxPromotedReserveRank": 20,
  "reviews": [{"orderId": 55, "reviewStartedAt": "2026-11-20T12:30:00Z", "elapsedMinutes": 42}],
  "refundPending": [{"orderId": 71}]
}
```

- `applicationCount`: 취소하지 않은 응모(`APPLIED`) 수.
- `occupied`: 확정 + 결제 대기 인원. 정원(`winnerLimit`)을 넘지 않는다(INV-FM-07). 보류 중인 자리도 결제 대기에 들어 있다.
- `selectionCounts`, `orderCounts`: 0명인 상태도 모두 담는다.
- `maxPromotedReserveRank`: 승격된 예비 중 가장 뒤 순번. 승격된 예비가 없으면 null. 승격 차례에 탈퇴가 확인돼 `NOT_NEEDED`가 된 예비는 세지 않는다.
- `reviews`: 결제 결과를 모르는 보류 주문. `elapsedMinutes`는 보류가 이어진 분(내림).
- 회원 ID는 내보내지 않는다. 주문 번호로 추적한다.
- 현재 시도가 아니어서 거절한 승인(계약 위반)은 저장하지 않으므로 summary에 없다. 로그 `FANMEETING_APPROVAL_NOT_CURRENT`로 확인한다. 늦은 승인·금액 불일치는 `refundPending`에 나온다.

## 결제 (공통 결제 API)

팬미팅은 결제 API를 따로 두지 않는다. 손님은 공통 결제 API를 쓴다([주문·결제 API](payment.md)). 처리 흐름은 [transactions.md 6·7절](../../domains/fanmeeting/transactions.md#6-결제-시작-공통-결제-api).

| 단계 | 호출 | 팬미팅 값 |
|---|---|---|
| 결제 준비 | `POST /api/payments` + 헤더 `Idempotency-Key` | `payableType = FAN_MEETING`, `payableId = orderId` |
| 승인 | `POST /api/payments/confirm` | 결제창 결과(`paymentKey`, `orderId` = 결제 모듈의 `pgOrderId`, `amount`) |
| 결제 상태 | `GET /api/payments/{paymentId}` | — |

- 금액은 주문 금액(참가비), 결제 기한은 `발급 + 60분`이다. 요청 본문의 금액·기한은 쓰지 않는다.
- 결제 대기(`PENDING_PAYMENT`) 주문이 아니거나 행사가 결제 진행 중(`PAYMENT_IN_PROGRESS`)이 아니면 409 `PAYMENT_NOT_PAYABLE`, 다른 회원 주문이면 404 `PAYMENT_TARGET_NOT_FOUND`, 기한이 지났으면 409 `PAYMENT_WINDOW_EXPIRED`다(공통 결제 오류 코드).
- 시도가 실패하면 **새 키**로 다시 결제를 준비한다.
- 참가비가 0원인 주문은 결제를 준비할 수 없다(409 `PAYMENT_NOT_PAYABLE`, `details.reason = "FREE_ORDER"`). 확정 API를 쓴다.

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
| 결제 대기가 아닌 주문의 포기·확정, 0원이 아닌 주문의 확정 | 409 | `ORDER_NOT_PAYABLE` |
| 결제 기한 지난 주문의 포기·확정 | 409 | `PAYMENT_WINDOW_EXPIRED` |
| 결제를 준비한 주문의 포기 | 409 | `DECLINE_NOT_ALLOWED` |

결제 준비·승인 오류는 공통 결제 오류 코드를 쓴다([주문·결제 API](payment.md#결제-오류)).

오류 코드 일부(`APPLICATION_*`, `ORDER_*`, `PAYMENT_*`)는 [공통 규칙 4번](README.md#공통-규칙)의 `도메인_상황` 형식과 달리 도메인 접두어가 없다. 명세 동결로 이름을 그대로 둔다.

## DTO 원칙

- 엔티티를 그대로 응답하지 않는다.
- `random_seed`, `lease_token`, 다른 회원 정보는 응답에 넣지 않는다.
- 팀 공통 `ErrorResponse` 형식([README.md](README.md))을 따른다.
- 회원 ID는 요청 본문이 아니라 인증 정보에서 얻는다.
- 다른 회원의 주문은 403이 아니라 404 `ORDER_NOT_FOUND`로 답한다.

## 공개 목록

GET /api/fan-meetings?status=SCHEDULED&page=0&size=20. 단일 status이며 생략하면 SCHEDULED·OPEN만 표시한다. 지난 행사는 status로 조회한다. CANCELLED는 필터를 지정해도 목록에서 제외하며 빈 목록을 반환한다.
page >= 0, 1 <= size <= 100이며 기본 0·20이다. 잘못된 파라미터는 400 INVALID_REQUEST다. 정렬은 event_id DESC다.
```json
{"items":[{"eventId":1,"title":"팬미팅","status":"SCHEDULED","entryOpensAt":null,"entryClosesAt":null,"meetingAt":"2026-11-25T03:00:00Z","winnerLimit":300,"feeAmount":50000}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```
목록과 상세는 시각 공개 판정을 공유한다. 게릴라 행사의 SCHEDULED·CANCELLED는 entryOpensAt=null, entryClosesAt은 OPEN에서만 공개한다.

## 오픈 전 수정·취소

PUT /api/organizer/fan-meetings/{eventId}는 생성 요청과 같은 모든 필드를 받는다.
```json
{"title":"변경된 팬미팅","entryOpensAt":"2026-11-20T03:00:00Z","firstNoticeScheduledAt":"2026-11-19T03:00:00Z","winnerLimit":300,"reserveLimit":100,"feeAmount":50000,"meetingAt":"2026-11-25T03:00:00Z","promotionClosesAt":null,"opensAtPublic":true}
```
성공은 200이며 상세 응답과 같은 필드(eventId, title, status, entryOpensAt, entryClosesAt, meetingAt, winnerLimit, reserveLimit, feeAmount)를 반환한다. organizer_id·status는 보존한다.

POST /api/organizer/fan-meetings/{eventId}/cancel은 본문 없이 호출하고 성공은 빈 본문 200이다. 이미 CANCELLED인 경우도 같은 응답이다.
최초 수정·취소는 SCHEDULED이고 기존 entry_opens_at보다 이전에만 가능하다. 정확히 같은 시각부터 거절한다.
없는 행사는 404 FAN_MEETING_NOT_FOUND, 다른 주최자는 404 FAN_MEETING_NOT_FOUND, 상태·시각 위반은 409 FAN_MEETING_NOT_EDITABLE이다.
일반 회원은 403 FORBIDDEN, 비로그인은 401 UNAUTHORIZED다. 새 설정의 도메인 검증은 400 INVALID_FAN_MEETING_SETTINGS이며 JSON·필수 필드는 400 INVALID_REQUEST다.
취소 이유 코드 BEFORE_OPEN·실행 회원 ID는 로그에 남기고 컬럼은 추가하지 않는다. 취소 상세는 200 CANCELLED, 응모는 409 APPLICATION_WINDOW_CLOSED다.
