# 팬미팅 API

- 담당: 홍승연
- 출처: 담당자 P0 구현 명세 8장, 6.6절. 처리 흐름은 [domains/fanmeeting/transactions.md](../../domains/fanmeeting/transactions.md)
- 상태: P0 설계 동결 (2026-11-10)

| Method | Endpoint | 설명 | 권한 |
|---|---|---|---|
| POST | `/api/admin/fan-meetings` | Event 생성 | ADMIN |
| GET | `/api/fan-meetings/{eventId}` | 상세. OPEN 전 `entry_opens_at`은 숨김. OPEN 중 `entryClosesAt` 포함 | 공개 |
| POST | `/api/fan-meetings/{eventId}/applications` | 응모·재응모 | 회원 |
| DELETE | `/api/fan-meetings/{eventId}/applications/me` | 응모 취소 | 회원 |
| GET | `/api/fan-meetings/{eventId}/applications/me` | 내 응모 상태 | 회원 |
| GET | `/api/fan-meetings/{eventId}/result/me` | 내 당첨·예비 결과 + `paymentDeadline` | 회원 |
| GET | `/api/fan-meetings/{eventId}/orders/me` | 내 주문·결제 상태 + `paymentDeadline` | 회원 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/payment-attempts` | 결제 시작. 헤더 `Idempotency-Key` 필수 | 회원 |
| POST | `/api/fan-meetings/{eventId}/orders/{orderId}/decline` | 결제 포기 | 회원 |
| GET | `/api/admin/fan-meetings/{eventId}/summary` | 상태, 응모 수, Selection·Order 상태별 인원, 정원 점유, 승격된 예비 최대 순번, `promotion_closes_at`, 보류 목록(경과 시간), `REFUND_PENDING` 목록, 계약 위반 승인 목록 | ADMIN |

관리자 API는 `role = ADMIN`만 호출할 수 있다. 권한이 없으면 403 `FORBIDDEN`, 로그인하지 않았으면 401 `UNAUTHORIZED`([공통 API](common.md)).

응모는 `UNIQUE(event_id, user_id)`로 중복을 막으므로 `Idempotency-Key`를 쓰지 않는다. 결제 시작만 `Idempotency-Key`를 받는다.

## 결제 시작 응답

주문 상태 확인이 먼저다. 주문이 `PENDING_PAYMENT`가 아니면 결제 모듈을 부르지 않는다.

| 주문 상태 | 같은 키·새 키 모두 |
|---|---|
| `PAID` | 409 `ORDER_NOT_PAYABLE` (클라이언트는 `/orders/me`로 확정을 확인) |
| `PAYMENT_REVIEW` | 409 `PAYMENT_UNDER_REVIEW` |
| `EXPIRED`, `CANCELLED`, `REFUND_PENDING` | 409 `ORDER_NOT_PAYABLE` |
| `PENDING_PAYMENT` | 결제 모듈 호출, 아래 표대로 |

| 결제 모듈 결과 | HTTP | 본문 |
|---|---|---|
| 새 시도 생성(READY) | 201 | `attemptId`, `status = READY`, `checkoutUrl`, `paymentDeadline` |
| 같은 키 재요청 (주문이 아직 `PENDING_PAYMENT`) | 200 | 그 시도의 **현재 상태**. READY면 `checkoutUrl` 포함, 그 외는 없음 |
| 다른 키, READY 시도 있음 | 409 | `PAYMENT_ATTEMPT_IN_PROGRESS`. 새 시도를 만들지 않는다 |
| 다른 키, UNKNOWN 시도 있음 | 409 | `PAYMENT_UNDER_REVIEW` |
| 다른 키, 이미 APPROVED 시도 있음(결과 전달 전) | 409 | `ORDER_NOT_PAYABLE` |
| 이전 시도가 모두 FAILED | 201 | 새 시도 |
| 결제 모듈이 기한 지남으로 거절 | 409 | `PAYMENT_WINDOW_EXPIRED` |
| 호출 실패·타임아웃 | 503 | `PAYMENT_UNAVAILABLE`. `attempt_requested_at`은 그대로 |

- "같은 키 재요청 = 200"은 주문이 아직 `PENDING_PAYMENT`일 때만이다. 결제 모듈 수준의 멱등(같은 키 → 같은 시도)은 그대로 유지되고, API 응답은 주문 상태가 우선한다.
- `FAILED` 상태를 받은 클라이언트는 **새 키**로 다시 결제를 시작한다.

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
| 결제 기한 지남 | 409 | `PAYMENT_WINDOW_EXPIRED` |
| 보류 중 결제 시도 | 409 | `PAYMENT_UNDER_REVIEW` |
| 처리 중인 결제 시도 있음 | 409 | `PAYMENT_ATTEMPT_IN_PROGRESS` |
| 결제 모듈 호출 실패 | 503 | `PAYMENT_UNAVAILABLE` |
| 모집 완료 | 409 | `FAN_MEETING_NOT_PAYABLE` |
| 결제 가능 상태 아님 | 409 | `ORDER_NOT_PAYABLE` |
| 결제를 시작한 주문의 포기 | 409 | `DECLINE_NOT_ALLOWED` |

오류 코드 일부(`APPLICATION_*`, `ORDER_*`, `PAYMENT_*`)는 [공통 규칙 4번](README.md#공통-규칙-proposed)의 `도메인_상황` 형식과 달리 도메인 접두어가 없다. 명세 동결로 이름을 그대로 둔다.

## DTO 원칙

- 엔티티를 그대로 응답하지 않는다.
- `random_seed`, `lease_token`, 다른 회원 정보는 응답에 넣지 않는다.
- 팀 공통 `ErrorResponse` 형식([README.md](README.md))을 따른다.
- 회원 ID는 요청 본문이 아니라 인증 정보에서 얻는다.
- 다른 회원의 주문은 403이 아니라 404 `ORDER_NOT_FOUND`로 답한다.
