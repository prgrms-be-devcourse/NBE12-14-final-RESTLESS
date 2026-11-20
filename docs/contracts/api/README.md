# API 계약

> 상태: 각 도메인 문서의 머리말을 따른다. 문서 머리말 양식과 상태 용어는 [contracts/README.md](../README.md)에 있다.
>
> 마지막 확인: 2026-11-20 (#290 요청 횟수 제한 절을 common.md에서 옮기고 한도를 `application.yml`과 대조. #271 공통 429 응답 추가. 엔드포인트 색인은 2026-11-16 대조)

- 경로: `/api/...` ([ADR-0002](../../decisions/0002-common-conventions.md))
- 인증: JWT Bearer. 토큰, Refresh 쿠키, 공개 경로, 역할 규칙은 [인증·회원 API](auth.md#인증-규칙)에 있다.

## 문서 목록

| 문서 | 범위 | 담당 |
|---|---|---|
| [auth.md](auth.md) | 회원가입, 로그인, 토큰, 프로필, 탈퇴, 인증 규칙 | 홍승연 |
| [payment.md](payment.md) | 주문 조회, 결제 준비·승인·조회, 손님 환불 | 이태호 |
| [ticketing.md](ticketing.md) | 공연, 회차, 좌석 선점, checkout, 발급 티켓 | 송혜민 |
| [drop.md](drop.md) | 드롭 등록·조회, 입장 제어, 재고 점유 | 이태호 |
| [fanmeeting.md](fanmeeting.md) | 팬미팅 생성, 응모, 추첨 결과, 팬미팅 주문 | 홍승연 |
| [auction.md](auction.md) | 경매, 입찰, 실시간 스트림, 크레딧 | 천종원 |
| [common.md](common.md) | (나뉨) 예전 링크 안내용. `auth.md`, `payment.md`, 이 문서의 공통 규칙으로 옮김 | - |

## 공통 규칙

1. 회원 식별 값은 요청 본문이 아니라 인증 정보에서 꺼낸다.
2. 자원을 만드는 경쟁 요청(좌석 선점, 재고 점유, 입찰, 결제 준비, 손님 환불)은 `Idempotency-Key` 헤더를 받는다.
3. 오류 응답 형식은 아래로 통일한다. 티켓팅의 `details`와 쿠폰의 `traceId`, `timestamp`를 합친 것이다.

```json
{
  "code": "SEAT_UNAVAILABLE",
  "message": "선택한 좌석 중 이미 선점된 좌석이 있습니다.",
  "details": { "seatIds": [11] },
  "traceId": "...",
  "timestamp": "2026-11-20T11:00:01Z"
}
```

   오류 처리 세부 규칙:

   - **도메인 오류**: `ErrorCode`(한 파일)에 `도메인_상황` 이름으로 추가하고 `throw new BusinessException(ErrorCode.X)`. 추가 정보는 `new BusinessException(ErrorCode.X, Map.of("seatIds", ids))`처럼 `details`로 준다. 없으면 `details`는 null.
   - **traceId**: 요청마다 서버가 만들어 응답 헤더 `X-Trace-Id`와 로그 줄(`[traceId]`)에 넣는다. 오류를 전달받으면 이 값으로 서버 로그를 찾는다.
   - **공통 처리**:

   | 상황 | 상태 | code | details |
   |---|---|---|---|
   | 입력 검증 실패 | 400 | `INVALID_REQUEST` | `fields`: 필드별 메시지 |
   | 필수 헤더 누락 (`Idempotency-Key` 등) | 400 | `INVALID_REQUEST` | `header`: 헤더 이름 |
   | 필수 쿼리 값 누락 | 400 | `INVALID_REQUEST` | `parameter`: 값 이름 |
   | 본문 해석 불가, 타입 불일치 | 400 | `INVALID_REQUEST` | |
   | 없는 경로 | 404 | `NOT_FOUND` | |
   | 지원하지 않는 메서드 | 405 | `METHOD_NOT_ALLOWED` | |
   | 업로드 크기 초과 | 413 | `FILE_SIZE_EXCEEDED` | |
   | 지원하지 않는 Content-Type | 415 | `UNSUPPORTED_MEDIA_TYPE` | |
   | 요청 횟수 한도 초과 ([한도](#요청-횟수-제한), `Retry-After` 초) | 429 | `TOO_MANY_REQUESTS` | |
   | 그 밖의 예외 | 500 | `INTERNAL_ERROR` | (로그에 스택과 traceId) |

4. 오류 코드는 `도메인_상황` 대문자 스네이크 케이스로 쓴다. 정상 거절(품절, 이미 받음)은 409 또는 422, 서버 오류는 5xx로 구분한다.
5. 일부 경로는 요청 횟수를 제한한다. 경로와 한도는 아래 절에 있다.

### 요청 횟수 제한

오류 응답은 위 공통 규칙 3의 JSON 형식을 따른다.

| 상황 | 상태 | code | details |
|---|---|---|---|
| 요청 횟수 한도 초과 | 429 | `TOO_MANY_REQUESTS` | |

429 응답의 `Retry-After` 헤더는 재시도까지 기다릴 초를 올림하여 준다.
기본값은 아래와 같으며 `restless.ratelimit.*`로 변경할 수 있다.

| Method | Endpoint | 기준 | 한도 |
|---|---|---|---|
| POST | `/api/auth/login` | IP | 1분에 20회 |
| POST | `/api/auth/signup` | IP | 1분에 10회 |
| POST | `/api/auth/refresh` | IP | 1분에 30회 |
| POST | `/api/drops/{dropId}/queue/entries` | 로그인 사용자 ID | 10초에 5회 |
| GET, HEAD | `/api/drops/{dropId}/queue/me` | 로그인 사용자 ID | 1초에 5회 |

Spring MVC가 같은 상태 조회 handler에 연결하는 HEAD는 GET과 동일한 사용자 counter를 소모한다.
첫 요청부터 고정 길이의 창을 센다. 규칙별로 분리하며 사용자 한도는 dropId와 무관하게 합산한다.
IP는 `request.getRemoteAddr()` 기준이며 `X-Forwarded-For`를 사용하지 않는다.
429로 거절된 상태 조회는 heartbeat를 갱신하지 않는다. 위 5개 외의 경로는 제한하지 않는다.
`restless.ratelimit.enabled=false`면 제한을 끈다. perf 프로필과 일반 테스트에서는 끈다.

## 전체 엔드포인트

API를 찾을 때 쓰는 색인이다. 요청·응답·오류는 문서 열의 도메인 문서가 정본이다. 새 API를 만들면 도메인 문서와 이 표에 함께 추가한다.

- 권한: `공개` 로그인 없이 호출, `로그인` 토큰 필요, 역할 이름은 그 역할만 (`SecurityConfig` 기준)
- `*` 경매 목록·상세·결과는 지금 공개 경로에 있지만, 경매 정책은 로그인 필요다. 공개 경로에서 빼는 작업이 남아 있다 ([auction.md](auction.md)).
- 구현: 2026-11-16 컨트롤러 기준. `미구현`은 문서에만 있는 계획이다.

| 도메인 | Method | Endpoint | 권한 | 구현 | 문서 |
|---|---|---|---|---|---|
| 인증 | POST | `/api/auth/login` | 공개 | 구현 | [auth.md](auth.md) |
| 인증 | POST | `/api/auth/logout` | 공개 | 구현 | [auth.md](auth.md) |
| 인증 | POST | `/api/auth/refresh` | 공개 | 구현 | [auth.md](auth.md) |
| 인증 | POST | `/api/auth/signup` | 공개 | 구현 | [auth.md](auth.md) |
| 회원 | GET | `/api/me/profile` | 로그인 | 구현 | [auth.md](auth.md) |
| 회원 | PATCH | `/api/me/profile` | 로그인 | 구현 | [auth.md](auth.md) |
| 회원 | POST | `/api/me/withdraw` | 로그인 | 구현 | [auth.md](auth.md) |
| 공연 | GET | `/api/concerts` | 공개 | 구현 | [ticketing.md](ticketing.md) |
| 공연 | GET | `/api/concerts/{concertId}` | 공개 | 구현 | [ticketing.md](ticketing.md) |
| 공연 | GET | `/api/concerts/{concertId}/performances` | 공개 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/performances/{performanceId}/seats` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | POST | `/api/ticket-reservations` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/ticket-reservations/active` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/ticket-reservations/{reservationId}` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | DELETE | `/api/ticket-reservations/{reservationId}` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | POST | `/api/ticket-reservations/{reservationId}/checkout` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/tickets` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/tickets/{ticketId}` | 로그인 | 구현 | [ticketing.md](ticketing.md) |
| 드롭 | POST | `/api/admin/drops` | ADMIN | 구현 | [drop.md](drop.md) |
| 드롭 | POST | `/api/admin/drops/{dropId}/publish` | ADMIN | 구현 | [drop.md](drop.md) |
| 드롭 | GET | `/api/drops` | 공개 | 구현 | [drop.md](drop.md) |
| 드롭 | GET | `/api/drops/{dropId}` | 공개 | 구현 | [drop.md](drop.md) |
| 드롭 | POST | `/api/drops/{dropId}/queue/entries` | 로그인 | 구현 | [drop.md](drop.md) |
| 드롭 | GET | `/api/drops/{dropId}/queue/me` | 로그인 | 구현 | [drop.md](drop.md) |
| 드롭 | POST | `/api/drops/{dropId}/reservations` | 로그인 | 구현 | [drop.md](drop.md) |
| 드롭 | GET | `/api/drops/{dropId}/reservations/{reservationId}` | 로그인 | 구현 | [drop.md](drop.md) |
| 드롭 | POST | `/api/drops/{dropId}/reservations/{reservationId}/cancel` | 로그인 | 구현 | [drop.md](drop.md) |
| 주문 | GET | `/api/me/orders` | 로그인 | 구현 | [payment.md](payment.md) |
| 주문 | GET | `/api/orders/{orderId}` | 로그인 | 구현 | [payment.md](payment.md) |
| 결제 | POST | `/api/payments` | 로그인 | 구현 | [payment.md](payment.md) |
| 결제 | POST | `/api/payments/confirm` | 로그인 | 구현 | [payment.md](payment.md) |
| 결제 | GET | `/api/payments/{paymentId}` | 로그인 | 구현 | [payment.md](payment.md) |
| 결제 | POST | `/api/payments/{paymentId}/refunds` | 로그인 | 구현 | [payment.md](payment.md) |
| 팬미팅 | GET | `/api/admin/fan-meetings/{eventId}/summary` | ADMIN | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | GET | `/api/fan-meetings/{eventId}` | 공개 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | POST | `/api/fan-meetings/{eventId}/applications` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | GET | `/api/fan-meetings/{eventId}/applications/me` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | DELETE | `/api/fan-meetings/{eventId}/applications/me` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | GET | `/api/fan-meetings/{eventId}/orders/me` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | POST | `/api/fan-meetings/{eventId}/orders/{orderId}/confirm` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | POST | `/api/fan-meetings/{eventId}/orders/{orderId}/decline` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | GET | `/api/fan-meetings/{eventId}/result/me` | 로그인 | 구현 | [fanmeeting.md](fanmeeting.md) |
| 팬미팅 | POST | `/api/organizer/fan-meetings` | ORGANIZER, ADMIN | 구현 | [fanmeeting.md](fanmeeting.md) |
| 경매 | GET | `/api/auctions` | 공개* | 구현 | [auction.md](auction.md) |
| 경매 | POST | `/api/auctions` | ORGANIZER, ADMIN | 구현 | [auction.md](auction.md) |
| 경매 | GET | `/api/auctions/{auctionId}` | 공개* | 구현 | [auction.md](auction.md) |
| 경매 | PATCH | `/api/auctions/{auctionId}` | ORGANIZER, ADMIN | 구현 | [auction.md](auction.md) |
| 경매 | DELETE | `/api/auctions/{auctionId}` | ORGANIZER, ADMIN | 구현 | [auction.md](auction.md) |
| 경매 | POST | `/api/auctions/{auctionId}/bids` | 로그인 | 구현 | [auction.md](auction.md) |
| 경매 | GET | `/api/auctions/{auctionId}/result` | 공개* | 구현 | [auction.md](auction.md) |
| 경매 | GET | `/api/auctions/{auctionId}/stream` | 로그인 | 구현 | [auction.md](auction.md) |
| 경매 | GET | `/api/me/bids` | 로그인 | 구현 | [auction.md](auction.md) |
| 크레딧 | GET | `/api/me/wallet` | 로그인 | 구현 | [auction.md](auction.md) |
| 크레딧 | POST | `/api/me/wallet/grants` | 로그인 | 구현 (prod 제외) | [auction.md](auction.md) |
| 티켓팅 | POST | `/api/ticket-reservations/{reservationId}/cancellations` | 로그인 | 미구현 | [ticketing.md](ticketing.md) |
| 티켓팅 | GET | `/api/ticket-reservations/{reservationId}/cancellations` | 로그인 | 미구현 | [ticketing.md](ticketing.md) |
| 드롭 | GET | `/api/admin/drops` | ADMIN | 미구현 (v1 범위 밖) | [drop.md](drop.md) |
| 드롭 | GET | `/api/admin/drops/{dropId}` | ADMIN | 미구현 (v1 범위 밖) | [drop.md](drop.md) |
| 드롭 | PATCH | `/api/admin/drops/{dropId}` | ADMIN | 미구현 (v1 범위 밖) | [drop.md](drop.md) |
| 드롭 | GET | `/api/admin/drops/{dropId}/summary` | ADMIN | 미구현 (v1 범위 밖) | [drop.md](drop.md) |
