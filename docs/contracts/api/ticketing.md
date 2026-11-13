> 구현 범위 변경 (DECIDED, 2026-11-12): 전용 멱등성 기록·실행권·접수 작업 자동 복구는 후속 구현이다. 아래 범용 202/statusUrl과 거절 응답 재현은 목표 계약이며 현재 MVP 보장으로 표시하지 않는다. 기존 자원의 중복 생성·변경 방지와 불명확 결제 보호·재확인은 필수다. [구현 범위](../../domains/ticketing/idempotency.md)를 따른다.
# 티켓팅 API

- 담당: 송혜민
- 출처: [design.md 8.3](../../domains/ticketing/design.md)
- 상태: Draft

| Method | Endpoint | 기능 |
|---|---|---|
| GET | `/api/concerts` | 공연 목록 (페이지 조회) |
| GET | `/api/concerts/{concertId}` | 공연 상세 |
| GET | `/api/concerts/{concertId}/performances` | 공연 회차 목록 |
| GET | `/api/performances/{performanceId}/seats` | 회차별 좌석 상태 (3초 폴링) |
| POST | `/api/ticket-reservations` | 좌석 선점 (`Idempotency-Key`) |
| GET | `/api/ticket-reservations/{reservationId}` | 예약 상세 |
| GET | `/api/ticket-reservations/active` | 활성 예약 조회, 복구 |
| DELETE | `/api/ticket-reservations/{reservationId}` | 선점 취소 |
| POST | `/api/ticket-reservations/{reservationId}/checkout` | 결제 시작 |
| POST | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 요청 |
| GET | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 상태 조회 |

## 공연 목록 조회

`GET /api/concerts?page=0&size=20`는 비로그인으로 공연 목록을 `200 OK`로 조회한다.
기존 공연 상세 DTO와 같은 필드를 목록 항목으로 사용한다.

```json
{
  "content": [
    {
      "concertId": 1,
      "venueId": 10,
      "title": "RESTLESS 공연",
      "status": "SCHEDULED",
      "createdAt": "2026-11-13T01:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

- page는 0부터 시작하고 기본값은 0이다. size는 기본 20, 허용 범위는 1~100이다.
- 생성 시각 내림차순으로 정렬하며, 같은 시각은 공연 ID 내림차순으로 정렬한다. 첫 회차 시작 시각순이 아닌 공연 등록 최신순이다.
- 공연이 없거나 페이지 범위를 벗어나면 content는 빈 배열이다. 전체 개수·페이지 수는 실제 전체 공연 수를 기준으로 반환한다.
- 음수 page, 범위 밖 size, 정수로 변환할 수 없는 파라미터는 `400 INVALID_REQUEST`다.
- 검색·상태 필터는 이번 범위에 포함하지 않는다. 상세 조회와 동일하게 저장된 공연을 반환하며 공개 상태별 필터는 정책 확정 후 적용한다.
- ConcertPage는 서비스 조회 결과이므로 concert/application/dto에 둔다. content는 기존 ConcertDetail을 재사용하며 Spring Data Page나 엔티티를 HTTP 응답에 직접 노출하지 않는다.
- ConcertPerformanceMappingTest에 비로그인 목록·최신순 및 동률 ID 정렬·페이지 기본값과 이동·범위 밖 빈 결과·잘못된 파라미터 400 검증을 작성했으며 아직 실행하지 않았다.

## 공연 상세·회차 목록 조회

두 API는 비로그인 조회를 허용한다. 좌석 화면 진입부터 로그인이 필요하다.

`GET /api/concerts/{concertId}`는 공연 상세를 `200 OK`로 반환한다.

```json
{
  "concertId": 1,
  "venueId": 10,
  "title": "RESTLESS 공연",
  "status": "SCHEDULED",
  "createdAt": "2026-11-13T01:00:00Z"
}
```

`GET /api/concerts/{concertId}/performances`는 해당 공연의 회차만 시작 시각 오름차순으로 반환한다.
응답의 performanceId로 `/api/performances/{performanceId}/seats`를 호출할 수 있다.

```json
[
  {
    "performanceId": 100,
    "concertId": 1,
    "venueId": 10,
    "layoutId": 20,
    "startsAt": "2026-11-20T10:00:00Z",
    "bookingOpensAt": "2026-11-13T02:00:00Z",
    "bookingClosesAt": "2026-11-20T09:00:00Z",
    "maxTicketsPerUser": 3,
    "status": "SCHEDULED"
  }
]
```

- 존재하는 공연에 회차가 없으면 `200 OK`와 빈 배열을 반환한다. 없는 공연은 상세·목록 모두 `404 NOT_FOUND`다.
- concertId가 양수가 아니거나 Long으로 변환할 수 없으면 `400 INVALID_REQUEST`다.
- 시각은 UTC Instant로 반환한다. 공연장 이름·주소, 소개·포스터 등은 현재 상세 응답에 포함하지 않는다.
- 상태값은 저장된 문자열을 반환한다. 예시의 SCHEDULED가 허용 상태 집합을 정의하지 않으며, 게시·공개 상태별 필터는 정책 확정 후 적용한다.
- 회차 목록의 기존 문서 경로 `/api/concerts/{concertId}/sessions`는 내부·API 회차 명칭 통일에 따라 `/performances`로 변경하며 별도 호환 경로는 제공하지 않는다.
- 서비스 조회 결과 DTO인 ConcertDetail·PerformanceInfo는 concert/application/dto에 둔다. Controller는 엔티티를 노출하지 않고 해당 DTO를 반환한다.
- ConcertPerformanceMappingTest의 기존 준비 데이터를 재사용해 비로그인 상세·회차 조회, 공연별 회차 분리·시작 시각 정렬 및 두 API의 없는 공연 404 검증을 작성했으며 아직 실행하지 않았다.

## 회차별 좌석 조회

`GET /api/performances/{performanceId}/seats`는 로그인이 필수이며,
`Authorization: Bearer <accessToken>` 헤더로 인증한다. 비로그인 요청은 `401 UNAUTHORIZED`로 응답한다.
공연 상세·회차 목록은 비로그인 조회를 허용하고, 회차를 클릭해 좌석 선택에 진입할 때부터 로그인을 요구한다.
프론트엔드는 로그인 완료 후 선택한 회차의 좌석 화면으로 복귀하도록 연결한다 (프론트엔드 구현은 별도).
`performanceId`를 내부 Performance ID로 전달하며, 해당 회차의 판매 좌석만 좌석 ID 오름차순으로 반환한다.

성공 (`200 OK`):

```json
[
  {
    "seatId": 10,
    "venueSeatId": 101,
    "sectionLabel": "A",
    "rowLabel": "1",
    "seatNumber": "1",
    "posX": 10.00,
    "posY": 20.00,
    "grade": "VIP",
    "price": 150000,
    "status": "AVAILABLE"
  }
]
```

`seatId`는 회차별 판매 좌석 ID이며 `venueSeatId`는 물리 좌석 ID다.
상태는 `AVAILABLE`, `HELD`, `SOLD` 중 하나다. 현재 상태를 조회하며 만료 처리나 선점은 수행하지 않는다.
회차에 판매 좌석이 없으면 빈 배열을 반환한다. 없는 회차는 `404 NOT_FOUND`,
Long으로 변환할 수 없는 performanceId는 `400 INVALID_REQUEST`로 응답한다.

## 활성 예약·예약 상세 조회

두 API 모두 로그인 필수이며 회원 ID는 `@LoginMember`로 인증 정보에서 추출한다.
요청 본문·경로·쿼리로 다른 회원의 ID를 받지 않는다.

- `GET /api/ticket-reservations/active`: 본인의 HELD/PAYMENT_PENDING/RECONCILING 예약을 생성 시각 내림차순, 동률이면 예약 ID 내림차순으로 반환한다. 회차마다 활성 예약이 있을 수 있어 결과는 배열이며, 없으면 `200 OK`와 빈 배열이다.
- `GET /api/ticket-reservations/{reservationId}`: 예약 ID와 로그인 회원 ID를 함께 조건으로 조회한다. 본인 예약은 종료 상태를 포함해 `200 OK`로 반환하며, 없는 예약과 타인 예약은 모두 `404 NOT_FOUND`다.

상세 응답 예시 (활성 목록은 아래 객체의 배열):

```json
{
  "reservationId": 5001,
  "performanceId": 100,
  "status": "HELD",
  "seats": [
    { "seatId": 10, "priceAmount": 100000 },
    { "seatId": 11, "priceAmount": 100000 }
  ],
  "holdExpiresAt": "2026-11-13T02:05:00Z",
  "holdExpired": false,
  "orderId": null,
  "paymentExpiresAt": null,
  "createdAt": "2026-11-13T02:00:00Z"
}
```

- `seatId`는 회차별 판매 좌석 ID이며 좌석 목록은 ID 오름차순이다. 가격은 현재 판매가가 아닌 최초 선점 이력의 가격이다.
- `orderId`, `paymentExpiresAt`는 연결 전에는 null이다. 공통 JSON 설정에 따라 null 필드는 생략될 수 있다.
- 조회는 잠금·상태 변경·기한 연장·만료 반환을 수행하지 않는다. 예약과 이력은 읽기 전용 REPEATABLE_READ 트랜잭션에서 같은 스냅샷으로 조회한다.
- 활성 목록은 저장된 예약 상태를 기준으로 한다. 만료 정리 전 HELD도 포함하며, DB UTC 시각이 선점 기한 이상이면 `holdExpired=true`다. 이 값은 결제 전 HELD 만료 여부이며 결제 가능 여부나 현재 좌석 소유권을 보장하지 않는다.
- PAYMENT_PENDING/RECONCILING은 최초 선점 기한이 지나도 `holdExpired=false`이고 활성 목록에 남는다. 결제 기한은 `paymentExpiresAt`를 사용하며 결제 진행·불명확 상태를 기한만으로 실패 처리하지 않는다.
- 비로그인은 `401 UNAUTHORIZED`, 잘못된 상세 예약 ID는 `400 INVALID_REQUEST`다. 요청 키·해시·타인의 회원 정보는 반환하지 않는다.
- TicketReservationServiceTest에 본인 상세·가격 스냅샷·읽기 전용 동작, 활성 상태와 회원별 목록 분리, 타인·없는 예약의 404 검증을 작성했으며 아직 실행하지 않았다.

## 좌석 선점

로그인 필수다. 회원 ID는 본문에서 받지 않고 `@LoginMember`로 인증된 토큰에서 추출한다.
`performanceId`와 `seatIds`는 양수여야 하며 좌석 목록은 비어 있거나 중복될 수 없다.
`seatIds`는 물리 좌석 ID가 아닌 조회 응답의 판매 좌석 `seatId`다.
`Idempotency-Key`는 필수이며 공백만 있는 값은 거절하고 최대 100자로 제한한다.
키의 대소문자·후행 공백은 구분하며, 좌석 ID의 순서 차이는 같은 요청으로 취급한다.

요청:

```http
POST /api/ticket-reservations
Authorization: Bearer <accessToken>
Idempotency-Key: 7f83a2b1-...
Content-Type: application/json

{ "performanceId": 100, "seatIds": [10, 11] }
```

성공 (`201 Created`):

```json
{
  "reservationId": 5001,
  "status": "HELD",
  "seatIds": [10, 11],
  "holdExpiresAt": "2026-11-12T02:30:00Z"
}
```

좌석 ID는 오름차순으로 반환하며, 잠금 획득 후 DB UTC 시각 기준으로 5분간 선점한다.
예매 시간은 시작 정각부터 마감 직전까지 허용한다. 좌석 하나라도 선점 불가하면 전체 요청을 롤백한다.
같은 사용자·키·회차·좌석 집합으로 재요청하면 기존 예약의 현재 상태와 최초 기한을 `201`로 반환한다.
만료·취소된 예약도 재생성하지 않는다. 만료된 결제 전 HELD 예약은 전체 반환 후 RELEASED 상태로 연결한다.
다른 회차나 좌석으로 같은 키를 재사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`다.
최초 요청의 5분 기한은 새로고침·재요청으로 연장되지 않는다.

선점 API의 오류 HTTP 상태:

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 헤더 누락, 유효하지 않은 본문·키, 중복 좌석 |
| 401 | `UNAUTHORIZED` | 인증 정보 없음 또는 유효하지 않은 토큰 |
| 404 | `NOT_FOUND` | 없는 공연 회차 |
| 409 | `SEAT_UNAVAILABLE` | 없는 판매 좌석, 다른 회차·배치, 판매 제외, 이미 선점·판매된 좌석 |
| 409 | `ACTIVE_RESERVATION_EXISTS` | 해당 사용자·회차의 활성 예약 존재 |
| 409 | `TICKET_LIMIT_EXCEEDED` | 확정+선점+신규 수량이 회차 한도 초과 |
| 409 | `BOOKING_NOT_OPEN`, `BOOKING_CLOSED` | 예매 시간 범위 밖 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 회차·좌석 요청 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금·생성 경합 등의 최대 3회 시도 소진. 같은 키로 재요청 가능 |

## 선점 취소

`DELETE /api/ticket-reservations/{reservationId}`는 로그인 필수이며 회원 ID는 `@LoginMember`로 받는다.
요청 본문과 Idempotency-Key는 필요하지 않다. 본인의 결제 전 HELD 예약 전체를 반환하며 일부 좌석만 취소하지 않는다.
성공은 본문 없는 `204 No Content`다.

```http
DELETE /api/ticket-reservations/5001
Authorization: Bearer <accessToken>
```

- 구매 관리 행 → 예약 → 판매 좌석 ID 오름차순으로 잠그고 소유자·상태·현재 선점 포인터를 재검증한다.
- 예약과 선점 이력은 RELEASED, 좌석은 AVAILABLE/current_hold_id=NULL로 변경하고 reserved_count를 반환 좌석 수만큼 줄인다. confirmed_count는 변경하지 않는다.
- 변경은 모두 같은 트랜잭션에서 커밋하거나 롤백하며 이력·최초 기한·요청 키는 보존한다.
- 본인의 이미 RELEASED인 예약에 반복 요청하면 204로 응답하며 좌석·수량을 다시 변경하지 않는다. 만료 처리와 경합해도 반환은 한 번만 반영한다.
- 만료 시각이 지난 결제 전 HELD도 취소 가능하다. PAYMENT_PENDING/RECONCILING/CONFIRMED 및 환불 상태는 이 API로 반환하지 않는다.
- HELD라도 주문 ID·결제 진입 시각·결제 기한이 있으면 취소하지 않는다. 결제·환불 취소 흐름은 별도 구현 대상이다.

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 유효하지 않은 예약 ID |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 404 | `NOT_FOUND` | 없는 예약 또는 타인의 예약 |
| 409 | `RESERVATION_CANNOT_CANCEL` | 결제 전 HELD/반환 완료 RELEASED 이외 상태 또는 결제 관련 필드 존재 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금 경합으로 최대 3회 시도 소진 |

좌석 소유 포인터·이력·카운터가 불일치하면 전체 롤백하며 서버 오류로 남긴다. 다른 소유자의 좌석을 강제로 반환하거나 수량을 임의 보정하지 않는다.
TicketReservationServiceTest에 본인·타인 취소, 중복 취소의 수량 보존, 결제 진행·결과 확인 중 취소 거절 테스트를 작성했으며 아직 실행하지 않았다.

## Checkout: 공통 주문 준비

`POST /api/ticket-reservations/{reservationId}/checkout`은 로그인 필수이며 `@LoginMember`로 본인 예약인지 확인한다.
요청 본문과 Idempotency-Key는 필요하지 않다. 최초·중복 요청 모두 `200 OK`이며 응답 예시는 다음과 같다.

```json
{
  "reservationId": 5001,
  "orderId": 7001,
  "totalAmount": 150000,
  "checkoutStartedAt": "2026-11-13T03:04:00Z",
  "paymentExpiresAt": "2026-11-13T03:09:00Z"
}
```

- 구매 관리 행 → 예약 → 판매 좌석 ID 오름차순으로 잠근다. 최초 진입은 결제 관련 필드가 없는 HELD만 허용한다.
- 모든 잠금을 획득한 뒤 DB UTC 시각으로 선점 기한과 각 좌석의 HELD/current_hold_id·선점 이력·회차 일치를 재검증한다. `now >= hold_expires_at`이면 거절하며 기존 만료 경로가 전체 반환한다.
- 최초 진입에서만 SeatHold의 가격 스냅샷으로 배송 없는 공통 TICKET 주문을 생성·연결하고 예약을 PAYMENT_PENDING으로 전환한다. totalAmount는 공통 주문 총액이다.
- 주문 항목은 서버의 회차 ID로 이름을 구성하고, 좌석 등급·판매 좌석 ID로 옵션을 구성한다. 클라이언트 금액·상품명은 받지 않는다.
- checkoutStartedAt은 잠금 후 DB UTC 시각, paymentExpiresAt은 그 시각부터 별도 5분이다. 최초 선점 기한·이력 기한·좌석 소유권·구매 수량은 유지한다.
- 기존 orderId가 있으면 기한 경과나 후속 상태와 무관하게 기존 주문과 최초 시각·기한을 반환한다. 새 주문·기한 연장은 없다. 이 응답은 현재 승인 가능함을 보장하지 않는다.
- 주문 생성과 예약 변경은 같은 READ_COMMITTED 트랜잭션에서 커밋/롤백한다. 교착·잠금 대기 실패는 전체 롤백 후 최대 3회 시도한다.
- 범용 Idempotency-Key 응답 재현은 후속이다. 결제 시도 생성, 티켓팅 PaymentResultHandler, PG 승인·티켓 발급 및 결제 이후 만료도 후속 구현이다. 현재 스케줄러는 결제 전 HELD만 반환한다.

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 유효하지 않은 예약 ID |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 404 | `NOT_FOUND` | 없는 예약 또는 타인 예약 |
| 409 | `RESERVATION_EXPIRED` | 최초 checkout의 선점 기한 도달·경과 |
| 409 | `RESERVATION_CANNOT_CHECKOUT` | 기존 주문이 없고 유효한 결제 전 HELD가 아님 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금 경합으로 최대 3회 시도 소진 |

좌석 소유권·이력 또는 연결 주문의 소유자·종류·최초 기한 불일치는 전체 롤백하고 서버 오류로 남긴다. 새 주문 생성이나 소유권 강제 보정으로 복구하지 않는다.
TicketCheckoutServiceTest의 MySQL Testcontainers 테스트 13개가 통과했다. 본인 인증·소유자 검증, 선점 가격 주문 생성, 중복·8개 동시 요청의 주문/기한 보존, 만료·취소 선행/후행 경합, 저장·flush 후 전체 롤백을 검증했다. 실행 명령과 범위는 [트랜잭션 문서](../../domains/ticketing/transactions.md#checkout-테스트-검증-2026-11-13)를 따른다.

## 오류 코드

| 코드 | 의미 |
|---|---|
| `SEAT_UNAVAILABLE` | 이미 선점된 좌석 포함 |
| `ACTIVE_RESERVATION_EXISTS` | 진행 중 예약 있음 |
| `TICKET_LIMIT_EXCEEDED` | 구매 제한 초과 |
| `RESERVATION_EXPIRED` | 선점 만료 |
| `BOOKING_NOT_OPEN` | 예매 시작 전 |
| `BOOKING_CLOSED` | 예매 마감 |
| `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 요청 |
| `SEAT_LOCK_TIMEOUT` | 잠금 대기 초과 |
| `RESERVATION_CANNOT_CANCEL` | 결제 전 선점 취소 불가 |
| `RESERVATION_CANNOT_CHECKOUT` | 최초 결제 진입 불가 |

위 선점 API 외 후속 API의 오류 HTTP 상태 코드는 해당 API 구현 시 정한다.

## 결제 기한 정책 (DECIDED)

최초 checkout에서 부여한 결제 기한 안에 서버가 유효한 승인 시도를 기록하고, 그 준비 트랜잭션이 성공적으로 커밋돼야 한다.
기한은 티켓팅 구매 관리·예약·좌석 잠금과 업무 검증 후 시도를 기록하는 DB UTC 시각으로 판단한다. payment_expires_at과 같거나 이후이면 신규 시도를 거절한다. 기한 내 기록 이후 주문 잠금 대기로 기한이 지나도 예약 기록·주문 변경이 함께 커밋되면 유효한 시도다.
기한 내 유효 시도의 성공 확인이 늦어져도 예매를 확정한다. 진행 중·불명확 시도는 기한 경과만으로 해제하지 않는다.
브라우저 시각이나 HTTP 접수 시각만으로 자격을 부여하지 않는다. 응답 필드와 상세 오류 코드는 후속 계약 대상이다.
[트랜잭션 설계](../../domains/ticketing/transactions.md)를 따른다.

## 멱등성 키 범위 (DECIDED)

키 범위는 인증된 사용자 + 서버가 정의한 작업 종류 + Idempotency-Key다.
회차·예약·주문 ID는 URL 경로를 포함한 요청 내용으로 비교한다. 같은 범위·키에 다른 요청은 IDEMPOTENCY_KEY_CONFLICT다.
MVP에서는 키를 삭제·재사용하지 않는다. 처리 중 동일 요청에는 202 Accepted, REQUEST_PROCESSING과 본인 작업 조회용 statusUrl을 반환한다.
같은 키의 다른 요청은 409 IDEMPOTENCY_KEY_CONFLICT다.
확정된 비즈니스 거절은 같은 결과를 반환하고, 전체 롤백·외부 부작용 없음이 확인된 내부 오류만 같은 키 재시도를 허용한다.
결과 불명확은 기존 작업 확인·복구로 연결하며 새 승인·환불을 만들지 않는다. PG 최종 실패 후 기한 내 새 승인 시도는 새 키를 사용한다.
상세 조회 경로·DTO·영속화 구조는 후속 설계 대상이다. [멱등성 계약](../../domains/ticketing/idempotency.md)을 따른다.

## MVP 공연장·배치 권한 정책 (DECIDED)

운영자가 공연장·배치를 사전 등록하며 주최자는 회차에 해당 공연장의 등록 배치만 선택한다.
예매 오픈 정각부터 회차 배치 변경을 금지한다. 배치 원본 수정으로 이를 우회해서도 안 된다.
MVP에서는 배치를 초기 데이터로 등록하고, 미등록 배치는 별도 요청 화면 없이 주최자 자료 전달·운영자 검토 후 등록한다. 배치 편집·등록 관리자 화면과 자동 좌석 생성은 제공하지 않는다. 초기 적재/추가 등록 절차·선택 API·공통 권한 매핑은 후속 구현 계약 대상이다. [배치 정책](../../domains/ticketing/venue-consistency.md)을 따른다.

## 구매 제한·활성 예약 (DECIDED)

사용자·회차별 HELD/PAYMENT_PENDING/RECONCILING 예약은 최대 1건이다. 다른 키로 요청해도 기존 활성 예약이 있으면 ACTIVE_RESERVATION_EXISTS로 거절한다.
동일 키 재전송은 기존 예약으로 연결한다. 확정 수량+선점 수량+신규 요청 수량이 회차별 한도를 넘으면 TICKET_LIMIT_EXCEEDED다.
환불 확정 전에는 확정 수량을 복원하지 않는다. 상세는 [구매 제한 설계](../../domains/ticketing/purchase-limit.md)를 따른다.

## MVP 적용 범위: 선점과 checkout (DECIDED)

선점은 사용자별 request_key UNIQUE와 최초 요청 해시 비교로 성공한 요청을 재현한다. 다른 회차·좌석의 키 재사용은 충돌이다.
checkout은 예약 잠금·기존 주문 참조로 중복 생성과 기한 연장을 막는다. 범용 사용자·작업별 키 충돌 계약은 MVP checkout에 적용하지 않는다.
결제 키는 공통 계약과 후속 조율한다. [세부 규칙](../../domains/ticketing/idempotency.md)을 따른다.

## 내부 회차 명칭

Java 엔티티·Repository는 Performance/PerformanceRepository, DB 테이블은 performances로 통일한다.
좌석 조회 API는 /api/performances/{performanceId}/seats로 통일하며 performanceId는 Performance의 ID다.
좌석 선점 요청 본문도 performanceId를 사용하며, 내부 Performance의 ID로 해석한다.
기존 concert-sessions 조회 경로와 선점 요청의 sessionId 필드는 제공하지 않는다.
선점 요청 DTO와 POST API는 위 명칭으로 구현했다. 테스트·동시성 실행 검증은 아직 하지 않았다.

## 공통 결제 대상 식별 (DECIDED, 2026-11-13)

티켓팅은 공통 결제의 `payableType=TICKET`, `payableId=예약 ID`로 연결한다. 공통 주문 ID와 결제창의 pgOrderId는 별개다. 금액은 예약에 연결된 공통 주문에서 확인한다.
현재 예약의 수락 시도 저장 기반과 내부 서비스의 대상 조회·승인 시작 검증을 구현했으며 티켓팅 PaymentResultHandler는 아직 등록하지 않았다. 따라서 이 매핑 합의가 티켓팅의 공통 결제 준비·승인 API 사용 가능을 뜻하지 않는다. checkout 응답과 기존 API 동작은 유지한다.
