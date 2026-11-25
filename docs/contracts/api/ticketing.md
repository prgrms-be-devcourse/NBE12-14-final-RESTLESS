# 티켓팅 API

> 담당: 송혜민
>
> 상태: IMPLEMENTED (전용 환불 경로 `.../cancellations`는 미구현, 환불은 공통 결제 API 사용)
>
> 관련 코드: `backend/src/main/java/com/restless/concert/api/`, `backend/src/main/java/com/restless/ticketing/api/`
>
> 마지막 확인: 2026-11-20 (#298 엔드포인트 표를 컨트롤러와, 오류 코드를 `ErrorCode`와, 좌석 압축을 `TicketSeatCompressionFilter`와 대조. 2026-11-16 결제 Handler 등록 여부 대조, 공연 목록과 상세는 `common.md`에서 이 문서로 일원화)

- 출처: [design.md 8.3](../../domains/ticketing/design.md)

> 구현 범위 변경 (DECIDED, 2026-11-12): 전용 멱등성 기록, 실행권, 접수 작업 자동 복구는 후속 구현이다. 아래 범용 202/statusUrl과 거절 응답 재현은 목표 계약이며 현재 MVP 보장으로 표시하지 않는다. 기존 자원의 중복 생성, 변경 방지와 불명확 결제 보호, 재확인은 필수다. [구현 범위](../../domains/ticketing/idempotency.md)를 따른다.

| Method | Endpoint | 기능 | 구현 |
|---|---|---|---|
| GET | `/api/concerts` | 공연 목록 (페이지 조회) | 구현 |
| GET | `/api/concerts/{concertId}` | 공연 상세 | 구현 |
| GET | `/api/concerts/{concertId}/performances` | 공연 회차 목록 | 구현 |
| POST | `/api/organizer/concerts` | 주최자 공연 등록 | 구현 |
| POST | `/api/organizer/concerts/{concertId}/publish` | 공연과 등록된 전체 회차 공개 | 구현 |
| GET | `/api/organizer/concerts` | 본인 공연 목록 (페이지 조회) | 구현 |
| GET | `/api/organizer/concerts/{concertId}` | 관리 가능한 공연 상세 | 구현 |
| PUT | `/api/organizer/concerts/{concertId}` | 본인 공연 정보 수정 | 구현 |
| GET | `/api/organizer/concerts/{concertId}/layouts` | 본인 공연장의 배치와 판매 가능 구역 조회 | 구현 |
| POST | `/api/organizer/concerts/{concertId}/performances` | 본인 공연 회차 등록 | 구현 |
| GET | `/api/organizer/concerts/{concertId}/performances` | 본인 공연 회차별 좌석·가격 설정 현황 | 구현 |
| PUT | `/api/organizer/performances/{performanceId}` | 본인 회차 일정·구매 한도 수정 | 구현 |
| PUT | `/api/organizer/concerts/{concertId}/section-prices` | 공개 전·회차 등록 전 공연 공통 구역 가격 설정 | 구현 |
| GET | `/api/performances/{performanceId}/seats` | 회차별 좌석 배치·가격·상태 최초 조회 | 구현 |
| GET | `/api/performances/{performanceId}/seats/status` | 회차별 판매 좌석 상태 갱신 | 구현 |
| GET | `/api/performances/{performanceId}/booking-availability` | 본인 회차별 구매 한도 조회 | 구현 |
| POST | `/api/ticket-reservations` | 좌석 선점 (`Idempotency-Key`) | 구현 |
| GET | `/api/ticket-reservations/{reservationId}` | 예약 상세 | 구현 |
| GET | `/api/ticket-reservations/active` | 활성 예약 조회, 복구 | 구현 |
| GET | `/api/tickets` | 본인 발급 티켓 목록 (페이지 조회) | 구현 |
| GET | `/api/tickets/{ticketId}` | 본인 발급 티켓 상세 | 구현 |
| DELETE | `/api/ticket-reservations/{reservationId}` | 선점 취소 | 구현 |
| POST | `/api/ticket-reservations/{reservationId}/checkout` | 결제 시작 | 구현 |
| POST | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 요청 | 미구현 |
| GET | `/api/ticket-reservations/{reservationId}/cancellations` | 환불 상태 조회 | 미구현 |
| POST | `/api/payments/{paymentId}/refunds` | 공통 결제 API를 통한 티켓 전액 환불 | 구현 |
| GET | `/api/payments/{paymentId}` | 공통 결제와 최근 환불 상태 조회 | 구현 |

## 주최자 MVP 범위 (DECIDED, 2026-11-24)

삭제 참조 정책: 드롭·경매가 연결된 공연은 삭제할 수 없으며 ADMIN도 동일하다. 다른 도메인 데이터를 자동 삭제·연결 해제하지 않는다. 해당 도메인 계약으로 참조를 확인하고 동시 연결 요청에도 정합성을 보존한다. 오류 응답 등 세부 구현 계약은 후속 설계한다.

기존 데이터 전환: 전환 전 기존 공연은 공개 상태를 유지하고 전환 이후 신규 등록 공연부터 비공개 초안으로 생성한다. 기존 거래·가격·티켓 이력을 보존하고 회차별 배치·등급·가격이 다른 공연은 자동 통일하지 않고 별도 확인한다. 이관의 세부 스키마·호환 계약은 후속 설계한다.

공연 등록 시 공연장과 그 공연장의 배치를 함께 선택한다. 모든 회차는 공연 공통 배치·구역별 등급·가격을 사용한다. 가격은 초안 준비 중 별도 단계에서 설정하며 등록 시 동시 입력을 요구하지 않는다. 회차 등록에서 배치·가격을 다시 받거나 회차별로 달리 수정하지 않는다. 현재 회차별 API·스키마는 변경이 필요하다. 공연 등록 → 공통 가격 설정 → 회차 등록 순서는 필수다. 모든 판매 가능 구역의 유효한 공통 등급·1원 이상 가격 설정 완료 후 회차와 판매 좌석을 함께 생성한다. 개별 등록은 회차·좌석 전체, 일괄 등록은 요청 전체 회차·좌석이 함께 성공하거나 함께 취소한다. 공통 등급·가격은 공개 전이고 등록된 회차가 없을 때만 변경할 수 있으며 ADMIN도 동일하다. 회차가 있으면 자동 가격 갱신 없이 변경을 거절한다. 공개 전·회차 없음 조건에서 공연장 또는 배치를 실제 변경하면 기존 공통 등급·가격을 같은 작업에서 모두 초기화한다. 새 배치 기준 재설정 전에는 회차 등록·공개를 허용하지 않는다. 동일 공연장·배치 재제출은 초기화하지 않는다.

[주최자 정책](../../domains/ticketing/organizer-policy.md)이 축소 확정 범위의 정본이다. 스키마·API 초안은 해당 문서 8절을 따른다. 아래 기존 API 본문은 현재 구현 기록이며 정책 변경을 아직 반영하지 않은 부분이 있다.

- MVP: 기본 관리 조회, 공연 초안 등록·개별 수정·조건부 삭제, 회차 개별·일괄 등록 및 개별 설정·수정·삭제, 공연과 모든 회차의 전체 공개.
- 일괄 등록은 요청당 1~10개다. 날짜·시작·예매 마감은 회차별 입력, 예매 시작·구매 한도는 공통 입력하며 배치·등급·가격은 공연 공통 설정을 사용한다. 하나라도 실패하면 회차·좌석 전체 취소한다.
- 공연 기간·양수 소요 시간은 회차 등록 전에 필수이며 기존 공연도 동일하다. 같은 공연의 시작 시점 중복·소요 시간 기준 겹침을 요청 내부와 기존 회차 모두 검사한다. 맞닿는 종료·시작은 허용한다.
- 모든 판매 구역 가격은 1원 이상이다. 공개 시 최소 1개 회차와 모든 회차의 준비 완료·미래 예매 시작을 검증한다. 최소 사전 공지 시간은 없다.
- 공개 후 제목·소개만 수정 가능하다. ADMIN은 모든 공연에 기본 주최자 기능을 수행하되 동일 변경·삭제 제한을 준수한다.
- DEFERRED: 일괄 수정·삭제, 회차 선택 공개, 공개 후 회차 추가, 비공개·재공개, 관리자 예외 수정·삭제, 일괄 등록 요청 식별자, 운영 정지·재개·이력·검토, 무료 공연, 관리 검색·필터 확장.
- 현재 구현은 신규 공연 초안, 공통 가격, 단건 회차와 좌석 생성, 고객의 초안 접근 차단, 공연 전체 공개를 제공한다. 일괄 등록·삭제 API는 미구현이다.

## 주최자 공연 등록과 관리 조회

`POST /api/organizer/concerts`는 로그인한 ORGANIZER 또는 ADMIN이 공연을 등록한다. 본문은 `venueId`, `layoutId`(각각 양수), `title`(공백만 제외, 최대 200자), 선택 항목 `description`(최대 5,000자), `periodStartDate`, `periodEndDate`, `durationMinutes`다. 기간은 공연장 기준 날짜 `YYYY-MM-DD`로 시작일과 종료일을 함께 입력하며 시작일은 종료일보다 늦을 수 없다. 소요 시간은 분 단위 양수다. 미입력 상세 정보는 null이다. 주최자는 요청의 값이 아니라 인증된 회원으로 지정한다. 등록자는 활성 회원이어야 한다. 공연장은 기존에 등록돼 있어야 하며 이 단계에서 공연장 상태별 등록 정책은 추가하지 않는다. 없는 공연장과 선택한 공연장에 속하지 않은 배치는 404 `NOT_FOUND`다.

성공하면 `201 Created`와 공연 ID, 공연장 ID·이름·주소, 공통 배치 ID(`layoutId`), 주최자 ID, 제목, 상태, 등록 시각, 입력한 상세 정보, 공개 여부(`published`), 최초 공개 시각(`publishedAt`, 공개 전 null), 공통 가격 설정 여부(`pricingConfigured`)를 반환한다. 등록된 공연은 비공개 초안이며 공개 전까지 고객 공연 목록·상세와 예매 경로에서 조회되지 않는다. 공연 등록은 회차나 판매 좌석을 생성하지 않는다.

`GET /api/organizer/concerts/{concertId}`는 같은 응답을 반환한다. ORGANIZER는 본인 공연만 조회할 수 있고 ADMIN은 다른 주최자의 공연도 조회할 수 있다. ORGANIZER의 타인 공연과 없는 공연은 모두 404다. 인증 실패는 401, 역할이 없으면 403, 잘못된 입력은 400, 존재하지 않는 공연장은 404다.

`GET /api/organizer/concerts?page=0&size=20`은 활성 ORGANIZER는 본인 공연을, ADMIN은 전체 공연을 최신 등록순(`createdAt`, `id` 내림차순)으로 조회한다. 응답은 `content`(관리 상세와 같은 공연 정보), `page`, `size`, `totalElements`, `totalPages`다. `page`는 0 이상, `size`는 1~100이다.

### 공연과 회차 정보 수정

`PUT /api/organizer/concerts/{concertId}`의 본문은 `title`, `description`, `periodStartDate`, `periodEndDate`, `durationMinutes`다. 전체 교체 방식으로 선택 항목을 생략하거나 null로 보내면 지워진다. 입력 제약과 응답은 공연 등록과 같다. 활성 주최자는 본인 공연을, 관리자는 모든 공연을 수정할 수 있으며, 공연장·소유자·상태는 바꾸지 않는다.

공연 제목과 소개는 예매 시작 또는 선점 이력과 관계없이 변경할 수 있다. 공연 기간이나 소요 시간을 바꾸려면 공연이 비공개이고 모든 회차가 예매 시작 전이고 선점 이력이 없어야 한다. 새 기간은 공연장 시간대 기준으로 모든 회차 시작일을 포함하고 변경된 소요 시간에 회차가 겹치지 않아야 하며, 회차가 있으면 기간과 소요 시간을 지울 수 없다. 기간·소요 시간을 그대로 보내고 제목·소개만 변경하는 경우에는 이 제한이 적용되지 않는다.

공연 수정의 오류 응답:
- 400 `INVALID_REQUEST`: 입력 제약 위반, 공개된 공연의 기간·소요 시간 변경, 회차가 있는데 기간·소요 시간을 지우는 요청, 새 기간이 회차 시작일을 포함하지 않거나 새 소요 시간으로 회차가 겹치는 경우.
- 409 `TICKET_SCHEDULE_CHANGE_CLOSED`: 비공개 공연이지만 예매가 시작된 회차나 선점·예약 이력(취소·만료 포함)이 있는 회차가 있어 기간·소요 시간을 바꿀 수 없는 경우.
- 404: ORGANIZER의 타인 공연과 없는 공연. 401·403은 다른 주최자 API와 같다.

`PUT /api/organizer/performances/{performanceId}`의 본문은 `startsAt`, `bookingOpensAt`, `bookingClosesAt`, `maxTicketsPerUser`이며 네 값을 모두 입력한다. 주최자는 본인 회차, 관리자는 모든 회차를 수정할 수 있다. 공연 기간, 예매 시간 순서와 구매 한도는 회차 등록과 동일하게 검증한다. 공개된 공연의 회차는 수정할 수 없다. 초안은 취소·만료를 포함한 선점·예약 이력이 없고 새 예매 시작 시각이 미래인 경우 수정할 수 있으며, 기존 예매 시작이 지났다면 새 예매 시작 시각으로 변경해야 한다. 같은 값의 재요청은 예매 시작 후에도 허용한다. 좌석 배치와 가격은 이 API에서 변경하지 않는다.

변경 기한 또는 선점 이력 제한에 걸리면 409 `TICKET_SCHEDULE_CHANGE_CLOSED`, 기간·시각 등 입력 오류는 400, 타인 공연·회차 또는 없는 대상은 404다. 선점은 회차 설정을 공유 잠금으로 읽고 수정은 쓰기 잠금을 얻어, 동시에 진행 중인 선점이 변경 전 설정으로 나중에 확정되지 않도록 한다. 공연 수정과 회차 등록·수정은 공연을 먼저 잠가 기간 검증을 직렬화한다.

`GET /api/organizer/concerts/{concertId}/performances`는 본인 공연의 회차를 시작 시각 오름차순으로 조회한다. 다른 주최자의 공연은 ORGANIZER에게 404이며 ADMIN은 조회할 수 있다. 각 항목의 `performance`는 기존 회차 정보이고, `sellableSeatCount`는 배치의 판매 가능 좌석 수, `configuredSeatCount`는 생성된 회차 좌석 수다. `pricingStatus`는 다음과 같다.

- `NOT_CONFIGURED`: 생성된 회차 좌석이 없다. 배치에 판매 가능 좌석 자체가 없는 경우도 포함한다.
- `CONFIGURED`: 판매 가능 배치 좌석과 회차 좌석이 정확히 대응하고 각 구역의 등급·가격이 동일하다.
- `INCONSISTENT`: 좌석 구성 또는 한 구역 내 등급·가격이 일치하지 않는다. 구성을 확인해야 한다.

`sections`는 좌석 구성이 일치하는 경우 구역별 `sectionLabel`, `seatCount`, `grade`, `price`, `uniform`을 표시한다. 한 구역의 가격이 서로 다르면 해당 `grade`와 `price`는 null이고 `uniform`은 false다. 좌석 구성이 다르면 `sections`는 빈 목록이다. 설정 현황은 예매 시간이나 좌석의 현재 판매 가능 여부를 의미하지 않는다. 회차가 없으면 빈 배열을 반환한다.

### 공연 전체 공개

`POST /api/organizer/concerts/{concertId}/publish`는 요청 본문 없이 공연과 등록된 모든 회차를 한 번에 공개한다. ORGANIZER는 본인 공연, ADMIN은 모든 공연에 요청할 수 있다. 공연 제목·공연장·공통 배치·공연 기간·소요 시간과 최소 한 회차가 필요하며, 판매 가능한 모든 구역의 1원 이상 공통 가격이 설정돼야 한다. 모든 회차는 공연 기간·시간 순서·구매 한도·배치 조건을 지키고 서로 겹치지 않아야 한다. 각 회차의 판매 좌석은 배치 좌석과 정확히 대응하고 공통 등급·가격과 일치해야 한다. 공개 시점의 DB 현재 시각보다 모든 회차의 예매 시작이 엄격히 미래여야 한다.

검증과 공개는 같은 트랜잭션에서 공연 행을 잠근 뒤 진행한다. 한 조건이라도 만족하지 못하면 400 `INVALID_REQUEST`이며 비공개가 유지된다. 인증 실패는 401, 역할 부족은 403, 타인의 공연 또는 없는 공연은 404다. 성공하면 200과 관리 공연 상세를 반환한다. 이미 공개된 공연에 대한 재요청은 권한 검사 후 현재 상세를 반환하며 기존 공개 시각을 유지한다. 최초 공개 후 고객 공연 및 회차 조회가 가능해지며 예매는 별도의 예매 시간 조건을 따른다.

## 주최자 회차 등록

`POST /api/organizer/concerts/{concertId}/performances`는 활성 ORGANIZER는 본인 공연에, ADMIN은 모든 공연에 회차를 추가한다. ORGANIZER의 타인 공연과 없는 공연은 404다.

```json
{
  "startsAt": "2027-03-01T10:00:00Z",
  "bookingOpensAt": "2027-02-01T01:00:00Z",
  "bookingClosesAt": "2027-03-01T09:00:00Z",
  "maxTicketsPerUser": 4
}
```

공연에는 배치, 모든 판매 구역의 공통 가격, 기간과 소요 시간이 먼저 설정돼 있어야 한다. 회차 시작 시각을 공연장의 시간대로 변환한 날짜가 공연 시작일 이상·종료일 이하여야 한다. 예매 시작은 예매 마감보다 빨라야 하고, 예매 마감은 회차 시작과 같거나 빨라야 한다. 구매 한도는 양수다. 모든 회차는 공연의 공통 배치를 사용하므로 요청에서 layoutId를 받지 않는다.

성공하면 `201 Created`와 회차 단일 항목을 반환하고, 상태는 `SCHEDULED`다. 회차와 공통 가격이 반영된 모든 판매 좌석을 같은 트랜잭션에서 생성한다. 하나라도 생성할 수 없으면 전체 요청을 롤백한다. 새 공연은 초안이므로 공개 전까지 고객 조회·예매 경로에 노출되지 않는다.

## 배치 조회와 구역별 가격 설정

`GET /api/organizer/concerts/{concertId}/layouts`는 본인 공연장에 등록된 배치 ID·이름·상태와 판매 가능한 구역별 좌석 수를 반환한다. 주최자는 본인 공연을, 관리자는 모든 공연을 조회할 수 있다. 판매 제외 좌석은 구역별 수량에 포함하지 않으며 구역이 없는 배치도 빈 목록으로 표시한다. 공연 등록 시 공연장에 속한 배치 ID를 선택한다.

`PUT /api/organizer/concerts/{concertId}/section-prices`는 공개 전이며 회차가 없는 본인 공연의 공통 구역별 등급과 가격을 전체 설정한다.

```json
{
  "sections": [
    { "sectionLabel": "A", "grade": "VIP", "price": 150000 },
    { "sectionLabel": "B", "grade": "R", "price": 120000 }
  ]
}
```

공연 공통 배치의 **판매 가능한 모든 구역을 정확히 한 번씩** 입력한다. 구역 표시명은 배치에 저장된 값과 정확히 일치해야 한다. 같은 구역의 모든 좌석은 같은 등급과 가격이며, 장애인석 등 개별 좌석 예외 가격은 지원하지 않는다. 등급은 공백만 제외 최대 20자, 가격은 원 단위 1원 이상의 정수다. 성공하면 저장된 공통 구역 가격 목록을 반환한다.

이 API는 아직 회차 좌석을 생성하지 않는다. 좌석은 회차 생성 요청과 함께 만들어지며, 공연 단위 공통 가격을 바꿀 수 있는 시점은 비공개·회차 없음으로 한정된다.

구역 누락·중복·미등록 구역 또는 잘못된 가격, 공개된 공연이나 회차가 있는 공연의 설정 요청은 400이다. 권한이 없는 공연과 없는 공연은 404다. 설정은 같은 트랜잭션에서 처리한다.

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
      "createdAt": "2026-11-13T01:00:00Z",
      "venueName": "공연장",
      "venueAddress": "서울",
      "description": "공연 소개",
      "periodStartDate": "2027-03-01",
      "periodEndDate": "2027-03-03",
      "durationMinutes": 120
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
- 공연이 없거나 페이지 범위를 벗어나면 content는 빈 배열이다. 전체 개수, 페이지 수는 실제 전체 공연 수를 기준으로 반환한다.
- 음수 page, 범위 밖 size, 정수로 변환할 수 없는 파라미터는 `400 INVALID_REQUEST`다.
- 검색, 상태 필터는 이번 범위에 포함하지 않는다. 상세 조회와 동일하게 저장된 공연을 반환하며 공개 상태별 필터는 정책 확정 후 적용한다.
- ConcertPage는 서비스 조회 결과이므로 concert/application/dto에 둔다. content는 기존 ConcertDetail을 재사용하며 Spring Data Page나 엔티티를 HTTP 응답에 직접 노출하지 않는다.
- 검증 테스트: `ConcertPerformanceMappingTest`

## 공연 상세와 회차 목록 조회

두 API는 비로그인 조회를 허용한다. 좌석 화면 진입부터 로그인이 필요하다.

`GET /api/concerts/{concertId}`는 공연 상세를 `200 OK`로 반환한다.

```json
{
  "concertId": 1,
  "venueId": 10,
  "title": "RESTLESS 공연",
  "status": "SCHEDULED",
  "createdAt": "2026-11-13T01:00:00Z",
  "venueName": "공연장",
  "venueAddress": "서울",
  "description": "공연 소개",
  "periodStartDate": "2027-03-01",
  "periodEndDate": "2027-03-03",
  "durationMinutes": 120
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

- 존재하는 공연에 회차가 없으면 `200 OK`와 빈 배열을 반환한다. 없는 공연은 상세, 목록 모두 `404 NOT_FOUND`다.
- concertId가 양수가 아니거나 Long으로 변환할 수 없으면 `400 INVALID_REQUEST`다.
- 회차 시각은 UTC Instant로 반환한다. 공연 응답은 공연장 이름·주소, 설명, 기간, 분 단위 소요 시간을 포함한다. 기존 공연이나 미입력 값은 null이며 포스터는 제공하지 않는다.
- 상태값은 저장된 문자열을 반환한다. 예시의 SCHEDULED가 허용 상태 집합을 정의하지 않는다.
- 비공개 공연(`is_published = FALSE`)은 고객 공연 목록에서 제외하고, 그 공연과 회차의 상세·회차 목록·좌석·좌석 상태·구매 한도 조회와 선점은 `404 NOT_FOUND`로 처리한다.
- 회차 목록의 기존 문서 경로 `/api/concerts/{concertId}/sessions`는 내부, API 회차 명칭 통일에 따라 `/performances`로 변경하며 별도 호환 경로는 제공하지 않는다.
- 서비스 조회 결과 DTO인 ConcertDetail, PerformanceInfo는 concert/application/dto에 둔다. Controller는 엔티티를 노출하지 않고 해당 DTO를 반환한다.
- 검증 테스트: `ConcertPerformanceMappingTest`

## 회차별 좌석 조회

`GET /api/performances/{performanceId}/seats`는 로그인이 필수이며,
`Authorization: Bearer <accessToken>` 헤더로 인증한다. 비로그인 요청은 `401 UNAUTHORIZED`로 응답한다.
공연 상세, 회차 목록은 비로그인 조회를 허용하고, 회차를 클릭해 좌석 선택에 진입할 때부터 로그인을 요구한다.
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

좌석 JSON이 2KB 이상이고 클라이언트가 gzip을 허용하면 서버는 gzip으로 응답할 수 있다. 압축 해제 후 JSON 필드와 의미는 동일하다. 압축은 이 좌석 조회 API에만 적용하며 `HTTP_COMPRESSION_ENABLED=false`로 비활성화할 수 있다.

### 좌석 상태 갱신

`GET /api/performances/{performanceId}/seats/status`는 인증된 사용자에게 해당 회차 판매 좌석의 `seatId`, `status`만 좌석 ID 오름차순으로 반환한다. 상태는 `AVAILABLE`, `HELD`, `SOLD`이며 조회 시점의 DB 상태다. 회차가 없으면 404, 좌석이 없으면 빈 배열, 비로그인은 401이다. 좌석 소유자나 배치·가격은 반환하지 않는다.

`frontend-test`는 최초 진입 시 `/seats`로 배치·가격과 상태를 받고, 사용자가 새로고침 버튼을 누르면 이 API의 `seatId`를 기준으로 상태만 갱신한다. 주기적인 자동 갱신은 하지 않는다. 목록이 최초 목록과 달라지면 전체 좌석을 다시 조회한다. 최초 목록에 없는 좌석을 임의로 생성하거나 실패한 상태 조회를 성공으로 처리하지 않는다. 좌석 상태는 조회 이후 달라질 수 있으며 선점 요청 시 서버가 최종 검증한다.

## 활성 예약과 예약 상세 조회

두 API 모두 로그인 필수이며 회원 ID는 `@LoginMember`로 인증 정보에서 추출한다.
요청 본문, 경로, 쿼리로 다른 회원의 ID를 받지 않는다.

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
- 조회는 잠금, 상태 변경, 기한 연장, 만료 반환을 수행하지 않는다. 예약과 이력은 읽기 전용 REPEATABLE_READ 트랜잭션에서 같은 스냅샷으로 조회한다.
- 활성 목록은 저장된 예약 상태를 기준으로 한다. 만료 정리 전 HELD도 포함하며, DB UTC 시각이 선점 기한 이상이면 `holdExpired=true`다. 이 값은 결제 전 HELD 만료 여부이며 결제 가능 여부나 현재 좌석 소유권을 보장하지 않는다.
- PAYMENT_PENDING/RECONCILING은 최초 선점 기한이 지나도 `holdExpired=false`이고 활성 목록에 남는다. 결제 기한은 `paymentExpiresAt`를 사용하며 결제 진행, 불명확 상태를 기한만으로 실패 처리하지 않는다.
- 비로그인은 `401 UNAUTHORIZED`, 잘못된 상세 예약 ID는 `400 INVALID_REQUEST`다. 요청 키, 해시, 타인의 회원 정보는 반환하지 않는다.
- 검증 테스트: `TicketReservationServiceTest`

## 본인 회차별 구매 한도 조회

`GET /api/performances/{performanceId}/booking-availability`는 로그인 회원의 해당 회차 구매 현황을 반환한다. `maximum`은 회차당 한도, `confirmed`는 확정 수량, `reserved`는 선점 수량, `remaining`은 `max(0, maximum - confirmed - reserved)`이다. `activeReservation`은 해당 회차의 HELD, PAYMENT_PENDING, RECONCILING 본인 예약 상세 또는 null이다. 예약이 없는 최초 사용자도 수량 0으로 조회하며, 타인의 수량은 공개하지 않는다. 회차가 없으면 404, 비로그인은 401이다.

조회 결과는 조회 시점의 안내이며 좌석을 확보하지 않는다. 다른 요청이 수량을 변경할 수 있으므로 실제 선점 시 서버는 기존 구매 관리 행 잠금 아래 한도와 활성 예약을 다시 확인한다. HELD는 기존 checkout 또는 취소로 이어가며, PAYMENT_PENDING, RECONCILING은 결제 확인으로 안내한다. 결제 진행 후에는 선점 취소 API를 사용할 수 없다.

## 좌석 선점

로그인 필수다. 회원 ID는 본문에서 받지 않고 `@LoginMember`로 인증된 토큰에서 추출한다.
`performanceId`와 `seatIds`는 양수여야 하며 좌석 목록은 비어 있거나 중복될 수 없다.
`seatIds`는 물리 좌석 ID가 아닌 조회 응답의 판매 좌석 `seatId`다.
`Idempotency-Key`는 필수이며 공백만 있는 값은 거절하고 최대 100자로 제한한다.
키의 대소문자, 후행 공백은 구분하며, 좌석 ID의 순서 차이는 같은 요청으로 취급한다.

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
같은 사용자, 키, 회차, 좌석 집합으로 재요청하면 기존 예약의 현재 상태와 최초 기한을 `201`로 반환한다.
만료, 취소된 예약도 재생성하지 않는다. 만료된 결제 전 HELD 예약은 전체 반환 후 RELEASED 상태로 연결한다.
다른 회차나 좌석으로 같은 키를 재사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`다.
최초 요청의 5분 기한은 새로고침, 재요청으로 연장되지 않는다.

선점 API의 오류 HTTP 상태:

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 헤더 누락, 유효하지 않은 본문, 키, 중복 좌석 |
| 401 | `UNAUTHORIZED` | 인증 정보 없음 또는 유효하지 않은 토큰 |
| 404 | `NOT_FOUND` | 없는 공연 회차 |
| 409 | `SEAT_UNAVAILABLE` | 없는 판매 좌석, 다른 회차, 배치, 판매 제외, 이미 선점, 판매된 좌석 |
| 409 | `ACTIVE_RESERVATION_EXISTS` | 해당 사용자, 회차의 활성 예약 존재 |
| 409 | `TICKET_LIMIT_EXCEEDED` | 확정+선점+신규 수량이 회차 한도 초과 |
| 409 | `BOOKING_NOT_OPEN`, `BOOKING_CLOSED` | 예매 시간 범위 밖 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 같은 키로 다른 회차, 좌석 요청 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금, 생성 경합 등의 최대 3회 시도 소진. 같은 키로 재요청 가능 |

## 선점 취소

`DELETE /api/ticket-reservations/{reservationId}`는 로그인 필수이며 회원 ID는 `@LoginMember`로 받는다.
요청 본문과 Idempotency-Key는 필요하지 않다. 본인의 결제 전 HELD 예약 전체를 반환하며 일부 좌석만 취소하지 않는다.
성공은 본문 없는 `204 No Content`다.

```http
DELETE /api/ticket-reservations/5001
Authorization: Bearer <accessToken>
```

- 구매 관리 행 → 예약 → 판매 좌석 ID 오름차순으로 잠그고 소유자, 상태, 현재 선점 포인터를 재검증한다.
- 예약과 선점 이력은 RELEASED, 좌석은 AVAILABLE/current_hold_id=NULL로 변경하고 reserved_count를 반환 좌석 수만큼 줄인다. confirmed_count는 변경하지 않는다.
- 변경은 모두 같은 트랜잭션에서 커밋하거나 롤백하며 이력, 최초 기한, 요청 키는 보존한다.
- 본인의 이미 RELEASED인 예약에 반복 요청하면 204로 응답하며 좌석, 수량을 다시 변경하지 않는다. 만료 처리와 경합해도 반환은 한 번만 반영한다.
- 만료 시각이 지난 결제 전 HELD도 취소 가능하다. PAYMENT_PENDING/RECONCILING/CONFIRMED 및 환불 상태는 이 API로 반환하지 않는다.
- HELD라도 주문 ID, 결제 진입 시각, 결제 기한이 있으면 취소하지 않는다. 결제 완료 후 환불은 공통 결제 환불 API를 사용한다.

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 유효하지 않은 예약 ID |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 404 | `NOT_FOUND` | 없는 예약 또는 타인의 예약 |
| 409 | `RESERVATION_CANNOT_CANCEL` | 결제 전 HELD/반환 완료 RELEASED 이외 상태 또는 결제 관련 필드 존재 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금 경합으로 최대 3회 시도 소진 |

좌석 소유 포인터, 이력, 카운터가 불일치하면 전체 롤백하며 서버 오류로 남긴다. 다른 소유자의 좌석을 강제로 반환하거나 수량을 임의 보정하지 않는다.
검증 테스트: `TicketReservationServiceTest`.

## Checkout: 공통 주문 준비

`POST /api/ticket-reservations/{reservationId}/checkout`은 로그인 필수이며 `@LoginMember`로 본인 예약인지 확인한다.
요청 본문과 Idempotency-Key는 필요하지 않다. 최초, 중복 요청 모두 `200 OK`이며 응답 예시는 다음과 같다.

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
- 모든 잠금을 획득한 뒤 DB UTC 시각으로 선점 기한과 각 좌석의 HELD/current_hold_id, 선점 이력, 회차 일치를 재검증한다. `now >= hold_expires_at`이면 거절하며 기존 만료 경로가 전체 반환한다.
- 최초 진입에서만 SeatHold의 가격 스냅샷으로 배송 없는 공통 TICKET 주문을 생성, 연결하고 예약을 PAYMENT_PENDING으로 전환한다. totalAmount는 공통 주문 총액이다.
- 주문 항목은 서버의 회차 ID로 이름을 구성하고, 좌석 등급, 판매 좌석 ID로 옵션을 구성한다. 클라이언트 금액, 상품명은 받지 않는다.
- checkoutStartedAt은 잠금 후 DB UTC 시각, paymentExpiresAt은 그 시각부터 별도 5분이다. 최초 선점 기한, 이력 기한, 좌석 소유권, 구매 수량은 유지한다.
- 기존 orderId가 있으면 기한 경과나 후속 상태와 무관하게 기존 주문과 최초 시각, 기한을 반환한다. 새 주문, 기한 연장은 없다. 이 응답은 현재 승인 가능함을 보장하지 않는다.
- 주문 생성과 예약 변경은 같은 READ_COMMITTED 트랜잭션에서 커밋/롤백한다. 교착, 잠금 대기 실패는 전체 롤백 후 최대 3회 시도한다.
- 범용 Idempotency-Key 응답 재현은 후속이다. TicketPaymentResultHandler가 (TICKET, 예약 ID)를 공통 결제 승인과 환불 API에 연결한다. 승인 결과, 보상 취소 및 손님 전액 환불 처리를 구현했다. PG 호출은 공통 결제 모듈과 pg.provider 설정이 담당하며 checkout 자체가 PG를 호출하지 않는다. 결제창 UI는 후속이다. 스케줄러는 결제 전 HELD와, 미확정 시도가 없는 PAYMENT_PENDING의 결제 기한 만료를 각각 처리한다. 진행, 불명확 시도는 기한만으로 반환하지 않는다. 자동 만료 뒤 checkout 재요청도 기존 주문, 최초 기한을 반환하며 재생성하지 않는다.

| HTTP | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 유효하지 않은 예약 ID |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 404 | `NOT_FOUND` | 없는 예약 또는 타인 예약 |
| 409 | `RESERVATION_EXPIRED` | 최초 checkout의 선점 기한 도달, 경과 |
| 409 | `RESERVATION_CANNOT_CHECKOUT` | 기존 주문이 없고 유효한 결제 전 HELD가 아님 |
| 409 | `SEAT_LOCK_TIMEOUT` | 잠금 경합으로 최대 3회 시도 소진 |

좌석 소유권, 이력 또는 연결 주문의 소유자, 종류, 최초 기한 불일치는 전체 롤백하고 서버 오류로 남긴다. 새 주문 생성이나 소유권 강제 보정으로 복구하지 않는다.
검증 테스트: `TicketCheckoutServiceTest`. 실행 범위는 [트랜잭션 문서](../../domains/ticketing/transactions.md#checkout-테스트-검증-2026-11-13)를 따른다.

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

## 결제 기한 정책 (IMPLEMENTED)

최초 checkout에서 부여한 결제 기한 안에 서버가 유효한 승인 시도를 기록하고, 그 준비 트랜잭션이 성공적으로 커밋돼야 한다.
기한은 티켓팅 구매 관리, 예약, 좌석 잠금과 업무 검증 후 시도를 기록하는 DB UTC 시각으로 판단한다. payment_expires_at과 같거나 이후이면 신규 시도를 거절한다. 기한 내 기록 이후 주문 잠금 대기로 기한이 지나도 예약 기록, 주문 변경이 함께 커밋되면 유효한 시도다.
기한 내 유효 시도의 성공 확인이 늦어져도 예매를 확정한다. 진행 중, 불명확 시도는 기한 경과만으로 해제하지 않는다.
브라우저 시각이나 HTTP 접수 시각만으로 자격을 부여하지 않는다. 응답 필드와 상세 오류 코드는 후속 계약 대상이다.
[트랜잭션 설계](../../domains/ticketing/transactions.md)를 따른다.

## 멱등성 키 범위 (IMPLEMENTED, 처리 중 202 응답은 미구현)

키 범위는 인증된 사용자 + 서버가 정의한 작업 종류 + Idempotency-Key다.
회차, 예약, 주문 ID는 URL 경로를 포함한 요청 내용으로 비교한다. 같은 범위, 키에 다른 요청은 IDEMPOTENCY_KEY_CONFLICT다.
MVP에서는 키를 삭제, 재사용하지 않는다. 처리 중 동일 요청에 202 Accepted, REQUEST_PROCESSING, 본인 작업 조회용 statusUrl을 주는 것은 목표 계약이며 아직 구현하지 않았다 (문서 머리의 구현 범위 변경 참고).
같은 키의 다른 요청은 409 IDEMPOTENCY_KEY_CONFLICT다.
확정된 비즈니스 거절은 같은 결과를 반환하고, 전체 롤백, 외부 부작용 없음이 확인된 내부 오류만 같은 키 재시도를 허용한다.
결과 불명확은 기존 작업 확인, 복구로 연결하며 새 승인, 환불을 만들지 않는다. PG 최종 실패 후 기한 내 새 승인 시도는 새 키를 사용한다.
상세 조회 경로, DTO, 영속화 구조는 후속 설계 대상이다. [멱등성 계약](../../domains/ticketing/idempotency.md)을 따른다.

## MVP 공연장과 배치 권한 정책 (DECIDED)

운영자가 공연장, 배치를 사전 등록하며 주최자는 회차에 해당 공연장의 등록 배치만 선택한다.
예매 오픈 정각부터 회차 배치 변경을 금지한다. 배치 원본 수정으로 이를 우회해서도 안 된다.
MVP에서는 배치를 초기 데이터로 등록하고, 미등록 배치는 별도 요청 화면 없이 주최자 자료 전달, 운영자 검토 후 등록한다. 배치 편집과 등록 관리자 화면은 제공하지 않는다. 주최자는 등록된 배치를 조회·선택하고, 회차를 등록하면 공통 가격으로 판매 좌석이 생성된다. 초기 적재와 추가 등록 절차는 후속이다. [배치 정책](../../domains/ticketing/venue-consistency.md)을 따른다.

## 구매 제한과 활성 예약 (IMPLEMENTED)

사용자, 회차별 HELD/PAYMENT_PENDING/RECONCILING 예약은 최대 1건이다. 다른 키로 요청해도 기존 활성 예약이 있으면 ACTIVE_RESERVATION_EXISTS로 거절한다.
동일 키 재전송은 기존 예약으로 연결한다. 확정 수량+선점 수량+신규 요청 수량이 회차별 한도를 넘으면 TICKET_LIMIT_EXCEEDED다.
환불 확정 전에는 확정 수량을 복원하지 않는다. 상세는 [구매 제한 설계](../../domains/ticketing/purchase-limit.md)를 따른다.

## MVP 적용 범위: 선점과 checkout (IMPLEMENTED)

선점은 사용자별 request_key UNIQUE와 최초 요청 해시 비교로 성공한 요청을 재현한다. 다른 회차, 좌석의 키 재사용은 충돌이다.
checkout은 예약 잠금, 기존 주문 참조로 중복 생성과 기한 연장을 막는다. 범용 사용자, 작업별 키 충돌 계약은 MVP checkout에 적용하지 않는다.
결제 키는 공통 계약과 후속 조율한다. [세부 규칙](../../domains/ticketing/idempotency.md)을 따른다.

## 내부 회차 명칭

Java 엔티티, Repository는 Performance/PerformanceRepository, DB 테이블은 performances로 통일한다.
좌석 조회 API는 /api/performances/{performanceId}/seats로 통일하며 performanceId는 Performance의 ID다.
좌석 선점 요청 본문도 performanceId를 사용하며, 내부 Performance의 ID로 해석한다.
기존 concert-sessions 조회 경로와 선점 요청의 sessionId 필드는 제공하지 않는다.
선점 요청 DTO와 POST API는 위 명칭으로 구현했다.

## 공통 결제 대상 식별 (IMPLEMENTED, 2026-11-13 결정)

티켓팅은 공통 결제의 `payableType=TICKET`, `payableId=예약 ID`로 연결한다. 공통 주문 ID와 결제창의 pgOrderId는 별개다. 금액은 예약에 연결된 공통 주문에서 확인한다.
티켓팅 결제 Handler(`TicketPaymentResultHandler`)가 등록돼 있어, 티켓 주문도 공통 결제 준비, 승인 API(`POST /api/payments`, `/confirm`)를 쓴다 (2026-11-16 코드 기준). checkout 응답과 기존 API 동작은 유지한다.

## 티켓 전액 환불

확정 예약의 환불은 공통 `POST /api/payments/{paymentId}/refunds`를 사용하며, 로그인한 본인의 결제만 요청할 수 있다. 결제 ID는 본인 주문 상세의 최근 결제 정보에서 확인할 수 있다. 요청 헤더 `Idempotency-Key`, 환불 사유와 응답 형식은 [공통 환불 API](payment.md#손님-전액-환불)를 따른다.

예약 전체를 수수료 없이 전액 환불한다. 공연 시작 1시간 전 정각부터 신규 요청을 거절하며 사용한 티켓은 환불할 수 없다. 요청 수락 후 결과 확정 전에는 티켓과 좌석을 보존한다. 취소 완료 시에만 티켓을 취소하고 좌석과 구매 확정 수량을 반환한다. 최종 거절 뒤 마감 전 새 키로 재요청할 수 있다.

공통 결제 조회 `GET /api/payments/{paymentId}`의 최근 환불 요약으로 진행·불명확·완료·거절 상태를 확인한다. 불명확한 결과는 공통 재확인 작업이 처리하며 좌석을 반환하지 않는다. 상세 상태와 잠금 순서는 [티켓팅 Handler 문서](../../domains/ticketing/payment-handler.md#정상-환불-요청-내부-준비-2026-11-22)를 따른다.

## 본인 발급 티켓 목록과 상세

로그인이 필수다. 회원 ID는 `@LoginMember`에서만 받으며 요청의 userId나 티켓번호로 권한을 부여하지 않는다. tickets.reservation_id가 가리키는 예약의 user_id로 DB 조회부터 소유자를 제한한다.

- `GET /api/tickets?page=0&size=20`: 본인 발급 티켓을 issuedAt 내림차순, 동률은 ticketId 내림차순으로 반환한다. page 기본 0, size 기본 20, 허용 1~100이다. 목록이 없거나 페이지 범위 밖이면 content는 빈 배열이며 totalElements/totalPages는 본인의 실제 전체 건수 기준이다.
- `GET /api/tickets/{ticketId}`: URL의 ticketId는 내부 BIGINT PK다. 타인 티켓과 없는 티켓은 모두 `404 NOT_FOUND`로 처리한다. ticketNo는 사용자에게 보여주는 기존 UUID이며 조회 권한이나 입장 토큰을 뜻하지 않는다.
- 비로그인은 두 API 모두 `401 UNAUTHORIZED`다. 음수 page, 범위 밖 size, page×size가 JPA offset 범위(Integer.MAX_VALUE)를 넘는 요청, 양수가 아닌 ticketId, 정수 변환이 불가능한 파라미터는 `400 INVALID_REQUEST`다.
- 티켓 레코드가 존재하면 저장된 상태와 사용, 취소 시각을 그대로 반환한다. ISSUED만 필터링하거나 취소 이력을 숨기지 않는다. 환불이 확정되면 CANCELLED와 취소 시각을 반환하며 입장 처리는 별도 범위다.
- 소유권은 예약 사용자 기준이다. 과거 티켓의 좌석이 재판매돼 current_hold_id가 다른 예약을 가리켜도 본인의 과거 티켓은 조회할 수 있으며 새 구매자의 티켓은 볼 수 없다.
- 가격은 tickets.price_amount의 발급 당시 금액이다. 공연명, 회차 시작 시각, 배치 표시명, 좌석 등급은 현재 카탈로그에서 읽으며 과거 시점의 별도 표시 스냅샷을 보장하지 않는다. 다른 도메인의 Repository를 직접 접근하지 않고 ConcertQueryService의 페이지 단위 일괄 조회를 사용한다.
- 조회는 읽기 전용 REPEATABLE_READ로 수행한다. 예약, 좌석 잠금, 발급/사용/취소 처리, 주문, 결제 상태 변경, 기한 연장을 하지 않는다.

상세 성공 (`200 OK`):

```json
{
  "ticketId": 101,
  "ticketNo": "8b4e35ae-179c-4bb8-97bd-4fc2c6d98bd3",
  "reservationId": 10,
  "orderId": 20,
  "performanceId": 100,
  "concertId": 1,
  "concertTitle": "RESTLESS 공연",
  "startsAt": "2026-11-20T10:00:00Z",
  "venueId": 5,
  "seat": {
    "seatId": 200,
    "venueSeatId": 300,
    "grade": "VIP",
    "sectionLabel": "A",
    "rowLabel": "1",
    "seatNumber": "3"
  },
  "priceAmount": 100000,
  "status": "ISSUED",
  "issuedAt": "2026-11-16T01:00:00Z",
  "usedAt": null,
  "cancelledAt": null
}
```

목록은 같은 구조의 티켓을 content에 담아 아래 메타데이터와 반환한다. Spring Data Page나 엔티티를 직접 응답하지 않는다.

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

검증 테스트: `TicketQueryApiTest`.

## 주문 상세의 티켓팅 결제 대상 연결

기존 `GET /api/orders/{orderId}`에서 본인의 티켓 주문을 조회하면 TicketOrderPayableResolver가 주문 ID와 연결된 예약을 찾아 아래 필드를 채운다. 새 HTTP API는 추가하지 않는다.

```json
{
  "payable": { "payableType": "TICKET", "payableId": 10 },
  "payableExpiresAt": "2026-11-16T01:05:00Z",
  "payment": { "paymentId": 25, "status": "READY" }
}
```

- payableId는 공통 주문 ID나 PG pgOrderId가 아닌 **티켓팅 예약 ID**다. payableExpiresAt은 최초 checkout에 저장된 payment_expires_at이며 hold_expires_at으로 대체하거나 연장하지 않는다.
- 주문의 본인 소유, 활성 회원 검증은 기존 공통 주문 상세가 Resolver 호출 전에 수행한다. 타인/없는 주문은 기존 404 ORDER_NOT_FOUND, 비로그인은 401이다.
- 최근 결제는 기존 공통 PaymentLookup이 (TICKET, 예약 ID, 회원 ID)로 조회한다. 아직 시도가 없으면 payment는 null이고, 실패 후 새 시도가 생성되면 가장 최근 시도가 표시된다.
- 만료, CONFIRMED, RELEASED 상태여도 대상 식별은 유지한다. 결제 가능 여부를 판정하는 TicketPaymentService.load와 구분하며, 최종 시작 가능 여부는 공통 결제 준비/승인 API에서 재검증한다.
- 연결된 예약이 없는 티켓 주문은 기존과 같이 payable/payableExpiresAt/payment가 null이다. Resolver는 읽기만 수행하고 주문, 예약, 결제 상태, 원래 기한을 바꾸지 않는다.
- 기존 OrderPayableResolver/OrderPayable 계약만 구현한다. 공통 주문, 결제 운영 코드와 DB 스키마 변경은 없다.

검증 테스트: `TicketCheckoutServiceTest.TicketOrderDetail`.
