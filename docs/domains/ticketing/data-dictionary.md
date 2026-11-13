# 공연·공연장·티켓팅 데이터 사전

- 기준: 현재 저장소의 Flyway CREATE와 통합 ALTER. 12개 테이블의 모든 컬럼을 설명한다.
- 이 문서는 컬럼의 의미를 설명한다. 실제 타입·NULL·제약의 정본은 아래 SQL이며, 상태 전이와 처리 정책은 관련 도메인 문서를 따른다.
- DB 컬럼이 존재하는 것과 JPA 엔티티·서비스 구현 완료는 다르다. 현재 Venue/VenueSection/VenueSeat, VenueLayout/LayoutSeat(LayoutSeatId), Concert/Performance, TicketSeat, TicketPurchaseLimit, TicketReservation, SeatHold, Ticket 매핑을 구현했다. 실제 선점·결제·환불과 조회 API 등 업무 로직은 단계적으로 구현한다.
- 전용 멱등성 테이블·실행권은 후속 구현으로, 이 데이터 사전의 현재 12개 테이블에 포함하지 않는다.

## 전체 구조

```text
공연장 → 구역 → 실제 좌석
   └─ 배치 → 배치 속 좌석
공연 → 회차 → 회차별 판매 좌석
사용자 → 예약 → 좌석별 선점 → 예매 확정 시 티켓
사용자·회차별 구매 관리 → 선점·확정 수량 제한
```

실제 좌석은 공간, 배치 속 좌석은 무대별 표시·사용 구성, 회차별 판매 좌석은 가격과 현재 소유권을 나타낸다.

## 공통 표기

- id는 내부 식별자다. layout_seats를 제외한 각 테이블의 id는 BIGINT AUTO_INCREMENT PK다.
- *_id는 연결 대상 식별자다. 단일 FK 외 복합 FK가 소속·회차의 일치도 검사한다.
- 금액은 원 단위 정수이며 0 이상이다. 시각은 DATETIME(6) UTC, 화면은 공연장 timezone으로 변환한다.
- NULL 가능은 아직 해당 단계에 도달하지 않았거나 관련 사실이 없을 수 있음을 뜻한다. 아래에서 별도 표기하지 않은 컬럼은 현재 SQL상 NOT NULL이다.
- 예시는 설명용이다. 공연장·구역·좌석 등의 status 값 집합은 이 문서에서 새로 확정하지 않는다.

## 테이블 목록

| 테이블 | 역할 |
|---|---|
| `venues` | 공연장 |
| `venue_sections` | 공연장 구역 |
| `venue_seats` | 실제 좌석 |
| `venue_layouts` | 좌석 배치 버전 |
| `layout_seats` | 배치에 포함된 좌석 |
| `concerts` | 공연 |
| `performances` | 공연 회차 |
| `performance_seats` | 회차별 판매 좌석 |
| `ticket_reservations` | 예약 |
| `seat_holds` | 좌석별 선점 이력 |
| `tickets` | 발급 티켓 |
| `ticket_purchase_limits` | 사용자·회차별 구매 관리 |

## 1. venues — 공연장

공연장 자체를 나타낸다. 공연 일시나 회차별 좌석 가격은 저장하지 않는다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 공연장 내부 식별자 | 자동 증가 PK |
| `name` | 공연장 이름 | 예: 올림픽홀 |
| `address` | 공연장 주소 | 소재지 |
| `timezone` | 공연장 현지 시간대 | 예: Asia/Seoul. UTC 시각의 현지 표시·해석에 사용 |
| `status` | 공연장 관리 상태 | 허용 상태값·전이 정책은 별도 정의 필요 |
| `created_at` | 서비스에 공연장을 등록한 시각 | 공연장 개장일이 아님 |

## 2. venue_sections — 공연장 구역

공연장 안의 구역을 관리한다. 같은 공연장 안에서 구역 코드는 유일하다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 구역 내부 식별자 | 자동 증가 PK |
| `venue_id` | 소속 공연장 ID | venues.id |
| `code` | 공연장 내 구역 코드 | 예: A, B |
| `name` | 구역 이름 | 예: A구역 |
| `floor` | 층 표시 | 예: 1F, 2F |
| `status` | 구역 관리 상태 | 회차별 판매 상태와 구분 |

## 3. venue_seats — 실제 좌석

공연장에 실제로 있는 좌석이다. 같은 구역의 열·번호 조합은 유일하며, 구역과 같은 공연장에 속해야 한다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 실제 좌석 내부 식별자 | 자동 증가 PK. 여러 배치·회차에서 재사용 |
| `venue_id` | 소속 공연장 ID | venues.id |
| `section_id` | 소속 구역 ID | venue_sections.id |
| `row_label` | 실제 좌석의 열 표시 | 예: 1, A |
| `seat_number` | 실제 좌석 번호 | 문자열로 보관, 예: 12 |
| `is_accessible` | 접근성 좌석 여부 | 접근성 지원 좌석 표시. 세부 운영 기준은 별도 정책 |
| `status` | 실제 좌석 관리 상태 | 가격·예매 여부를 나타내지 않음 |

## 4. venue_layouts — 좌석 배치 버전

무대 구성별 좌석 배치다. 공연장·배치 코드·버전 조합은 유일하고 version은 양수다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 배치 내부 식별자 | 자동 증가 PK |
| `venue_id` | 배치 소속 공연장 ID | venues.id |
| `layout_code` | 배치 종류 코드 | 예: BASIC_STAGE |
| `version` | 같은 배치 코드의 버전 | 예: 1, 2 |
| `name` | 배치 이름 | 예: 기본 무대 배치 v1 |
| `status` | 배치 관리 상태 | 허용 상태값·전이는 별도 정의 필요 |
| `published_at` | 배치 게시 시각 | 현재 스키마는 NOT NULL. 초안 상태를 도입하려면 이 조건과 함께 검토 |

## 5. layout_seats — 배치에 포함된 좌석

특정 배치에서 사용할 실제 좌석과 표시 정보를 연결한다. layout_id + venue_seat_id가 복합 PK다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `layout_id` | 소속 배치 ID | venue_layouts.id, 복합 PK 일부 |
| `venue_seat_id` | 실제 좌석 ID | venue_seats.id, 복합 PK 일부 |
| `venue_id` | 소속 공연장 ID | 배치·실제 좌석과의 소속 일치 검증 |
| `section_label` | 배치에서 표시할 구역명 | 예: VIP A구역 |
| `row_label` | 배치에서 표시할 열 | 실제 좌석의 기본 표시와 구분하여 보존 |
| `seat_number` | 배치에서 표시할 좌석 번호 | 배치별 표시 정보 |
| `pos_x` | 좌석도 가로 좌표 | DECIMAL(10,2), 화면 표현용. 좌표 단위는 후속 UI 계약 |
| `pos_y` | 좌석도 세로 좌표 | DECIMAL(10,2), 화면 표현용 |
| `is_sellable` | 이 배치에서 판매 가능한지 | 무대 등에 가려지는 좌석은 제외 가능 |

## 6. concerts — 공연

공연 자체를 나타낸다. 현재 구조에서는 하나의 공연이 한 공연장에 속하며 여러 회차를 가질 수 있다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 공연 내부 식별자 | 자동 증가 PK |
| `venue_id` | 공연장 ID | venues.id |
| `seller_id` | 등록자·주최자 사용자 ID | users.id. 주최자 권한은 서비스에서 검증 |
| `title` | 공연 제목 | 예: RESTLESS 콘서트 |
| `status` | 공연 관리 상태 | 허용 상태값·전이 정책은 별도 정의 필요 |
| `created_at` | 공연 정보 등록 시각 | 공연 시작 시각과 다름 |

## 7. performances — 공연 회차

날짜·시간이 정해진 공연 한 회차다. 코드의 Performance이 이 테이블에 대응한다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 회차 내부 식별자 | 자동 증가 PK |
| `concert_id` | 소속 공연 ID | concerts.id |
| `venue_id` | 회차 공연장 ID | 공연·배치와 같은 공연장이어야 함 |
| `layout_id` | 이 회차가 사용하는 배치 ID | venue_layouts.id. 예매 오픈 이후 변경 금지 |
| `starts_at` | 공연 시작 시각 | 화면에서는 공연장 시간대로 변환 |
| `booking_opens_at` | 신규 예매 오픈 시각 | 신규 좌석 선점 시작 기준 |
| `booking_closes_at` | 신규 예매 마감 시각 | 기존 유효 예약의 별도 결제 기한과 구분 |
| `max_tickets_per_user` | 회차별 사용자 구매 한도 | 양수. 확정 수량+선점 수량의 상한 |
| `status` | 회차 관리 상태 | 허용 상태값·전이 정책은 별도 정의 필요 |

## 8. performance_seats — 회차별 판매 좌석

특정 회차에서 판매하는 좌석이다. 같은 실제 좌석도 회차별로 가격·등급·판매 상태가 다를 수 있다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 판매 좌석 내부 식별자 | 선점 API의 seatIds가 가리키는 ID |
| `performance_id` | 대상 회차 ID | performances.id |
| `layout_id` | 회차가 선택한 배치 ID | performances.layout_id와 일치 |
| `venue_seat_id` | 실제 좌석 ID | (layout_id, venue_seat_id)로 layout_seats 참조 |
| `grade` | 이 회차의 좌석 등급 | 예: VIP, R. 구체적인 값은 후속 정책 |
| `price` | 이 회차의 판매 가격 | 원 단위 BIGINT, 0 이상 |
| `status` | 현재 판매 상태 | AVAILABLE, HELD, SOLD |
| `current_hold_id` | 현재 소유권의 선점 기록 ID | seat_holds.id. AVAILABLE이면 NULL, HELD/SOLD이면 필수 |

## 9. ticket_reservations — 예약

사용자 한 명이 한 회차에서 진행하는 예매 한 건이다. 한 예약에 여러 좌석 선점이 연결될 수 있다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 예약 내부 식별자 | 자동 증가 PK |
| `user_id` | 예약 사용자 ID | users.id. 요청 본문 대신 인증 정보로 결정 |
| `performance_id` | 예약 회차 ID | performances.id |
| `order_id` | 공통 주문 ID | NULL 가능. 최초 checkout에서 생성·연결, 주문당 예약 최대 1건 |
| `request_key` | 최초 선점 요청 키 | 사용자별 UNIQUE. 대소문자·후행 공백 구분 |
| `hold_request_hash` | 최초 선점 요청 내용 비교값 | BINARY(32), SHA-256. 기존 행은 NULL 가능, 신규 예약은 서비스에서 기록 |
| `status` | 예약 진행 상태 | 아래 예약 상태 설명 참고 |
| `hold_expires_at` | 최초 선점 기한 | 선점부터 5분. 결제 진입 이후 이 값만으로 반환 판단 금지 |
| `checkout_started_at` | 최초 결제 진입 시각 | NULL 가능. 중복 진입으로 갱신하지 않음 |
| `payment_expires_at` | 별도 결제 기한 | NULL 가능. 최초 결제 진입부터 5분, 승인 시도 기록의 기한 |
| `created_at` | 예약 생성 시각 | 최초 선점 성공 시 기록 |

## 10. seat_holds — 좌석별 선점 이력

어떤 예약이 어떤 판매 좌석을 확보했는지 기록한다. 2좌석 예약이면 선점 기록도 2개다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 선점 기록 내부 식별자 | performance_seats.current_hold_id가 참조 |
| `reservation_id` | 소속 예약 ID | ticket_reservations.id |
| `performance_id` | 회차 ID | 예약·판매 좌석의 회차와 같아야 함 |
| `performance_seat_id` | 선점한 판매 좌석 ID | performance_seats.id |
| `price_amount` | 선점 당시 좌석 가격 | 원 단위 스냅샷, 0 이상 |
| `expires_at` | 선점 기록의 만료 시각 | 예약·승인 상태와 함께 판단. checkout에서 변경하지 않음. 결제 진입 후 별도 payment_expires_at으로 판단 |
| `status` | 선점 이력 상태 | HELD, CONFIRMED, RELEASED |
| `created_at` | 선점 기록 생성 시각 | 이력 추적 |
| `released_at` | 선점 해제 시각 | NULL 가능. 환불 후 CONFIRMED 이력은 보존 |

## 11. tickets — 발급 티켓

예매 확정 후 좌석별로 발급하는 티켓이다. 동일 예약·판매 좌석에는 최대 한 장이며 취소 이력도 보존한다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 티켓 내부 식별자 | 자동 증가 PK |
| `reservation_id` | 발급 근거 예약 ID | ticket_reservations.id |
| `performance_id` | 공연 회차 ID | 예약·좌석과 같은 회차 |
| `performance_seat_id` | 티켓의 판매 좌석 ID | performance_seats.id |
| `ticket_no` | 사용자에게 제공하는 고유 티켓번호 | UUID v4 문자열로 발급하는 정책. VARCHAR(50), UNIQUE. 내부 PK와 다름 |
| `price_amount` | 티켓에 기록한 좌석 금액 | 원 단위, 0 이상. 할인·결제 금액 배분 방식은 후속 조율 |
| `status` | 티켓 상태 | 발급·사용·취소 등의 허용 상태값과 전이는 후속 정의 |
| `issued_at` | 티켓 발급 시각 | 예매 확정 트랜잭션에서 기록 |
| `used_at` | 티켓 사용 처리 시각 | NULL 가능. 입장 처리 기능은 별도 설계 |
| `cancelled_at` | 티켓 취소 시각 | NULL 가능. 환불 확정 후 취소 반영 |

## 12. ticket_purchase_limits — 사용자·회차별 구매 관리

사용자·회차별 한 행만 존재한다. 여러 탭의 요청은 이 행 잠금으로 직렬화한다.

| 컬럼 | 의미 | 설명 |
|---|---|---|
| `id` | 구매 관리 행 내부 식별자 | 자동 증가 PK |
| `user_id` | 관리 대상 사용자 ID | users.id |
| `performance_id` | 관리 대상 회차 ID | performances.id |
| `reserved_count` | 현재 확보 중인 좌석 수 | 선점·결제 진행·결과 불명확 수량 포함, 0 이상 |
| `confirmed_count` | 확정 구매 수량 | 환불 확정 전까지 포함, 0 이상 |

## 상태와 수량을 읽는 방법

예약 상태는 설계상 HELD, PAYMENT_PENDING, RECONCILING, CONFIRMED, RELEASED, REFUND_PENDING, REFUND_RECONCILING, CANCELLED다.
활성 예약은 HELD/PAYMENT_PENDING/RECONCILING이며 사용자·회차별 최대 1건이다.
좌석의 HELD는 결제 진행 중에도 유지된다. 현재 소유자는 HELD 이력 검색이 아니라 current_hold_id로 판단한다.
환불 후에도 선점의 CONFIRMED 이력은 보존하므로, 이것만으로 현재 유효 티켓을 판단하지 않는다.

```text
선점: reserved_count +N
결제 확정: reserved_count -N, confirmed_count +N
선점 취소·만료 반환: reserved_count -N
환불 확정: confirmed_count -N
항상: reserved_count + confirmed_count <= 회차별 max_tickets_per_user
```

결제·환불 결과 불명확 상태에서는 수량과 좌석을 유지한다.
카운터 상한·활성 예약·시간·상태 전이는 서비스 잠금과 검증이 필요하며 FK/CHECK만으로 모두 보장되지 않는다.

## SQL과 정책 정본

- [공연장·공연 CREATE](../../../backend/src/main/resources/db/migration/V20261111_0910__ticketing_create_venue_and_concert_tables.sql)
- [티켓팅 CREATE](../../../backend/src/main/resources/db/migration/V20261111_1000__ticketing_create_tables.sql)
- [통합 ALTER](../../../backend/src/main/resources/db/migration/V20261112_1020__ticketing_enforce_integrity_and_hold_request_keys.sql)
- [공연장 소속·배치 정책](venue-consistency.md), [예약·좌석 관계 제약](reservation-seat-consistency.md)
- [현재 좌석 소유권](seat-ownership.md), [구매 제한·수량](purchase-limit.md)
- [결제 트랜잭션](transactions.md), [PaymentResultHandler](payment-handler.md), [만료](expiration.md)
- [티켓 발급](ticket-issuance.md), [MVP 중복 요청 방지와 후속 멱등성 설계](idempotency.md)
