# Ticketing 도메인

담당자: 송혜민

| 문서 | 내용 |
|---|---|
| [data-dictionary.md](data-dictionary.md) | 공연장·공연·티켓팅 12개 테이블의 모든 컬럼 의미와 예시 |
| [design.md](design.md) | 담당자 설계 원본과 MySQL 정합성 보정 |
| [seat-ownership.md](seat-ownership.md) | 확정된 MySQL 좌석 소유권·잠금·반환 설계 |
| [ticket-issuance.md](ticket-issuance.md) | 확정된 UUID 티켓번호와 중복 발급 방지 |
| [transactions.md](transactions.md) | 결제 준비·PG 호출·결과 확정의 트랜잭션 경계 |
| [expiration.md](expiration.md) | 승인 시도 유무별 만료와 전체 좌석 반환 |
| [idempotency.md](idempotency.md) | MVP 중복 방지와 후속 전용 테이블·실행권 설계 |
| [venue-consistency.md](venue-consistency.md) | 공연장 소속·회차 배치 일치 복합 FK |
| [payment-handler.md](payment-handler.md) | 결제 Handler별 상태 전이와 공통 계약 차이 |
| [reservation-seat-consistency.md](reservation-seat-consistency.md) | 예약·선점·티켓 회차 및 현재 소유 좌석 일치 |
| [purchase-limit.md](purchase-limit.md) | 사용자·회차 관리 행 잠금과 활성 예약·수량 제한 |
| [API 계약](../../contracts/api/ticketing.md) | 원본에서 취합한 API |
| [통합 ERD](../../architecture/erd.md) | 도메인 사이 연결 |

design.md는 Notion 원본을 바탕으로 하며, 합의된 변경은 별도 정본 링크와 함께 반영한다. Notion 첨부 이미지(`attachment:`)는 GitHub에서 보이지 않으므로, 필요한 그림은 mermaid로 바꾸거나 이미지 파일을 이 폴더에 넣는다.

Drop 문서([../drop/](../drop/))처럼 policy, invariants, state-machines, transactions, data-model로 나눌지는 담당자가 정한다.

## 매핑 테스트 구성

대표 저장·조회에 상태·이력·NULL/non-NULL 확인을 통합하고, 같은 제약의 반복 사례를 줄였다.
별개의 FK·UNIQUE·CHECK를 단지 비슷한 SQL이라는 이유로 제거하지 않는다.

| 테스트 | 실행 사례 수 | 남긴 검증 |
|---|---:|---|
| VenueMappingTest | 4 | 공연장·구역·좌석 조회, 소속 및 구조 중복 제약 |
| VenueLayoutMappingTest | 5 | 복합 키·배치 버전·좌표, 배치/좌석 소속과 버전 제약 |
| ConcertPerformanceMappingTest | 8 | 공연·회차 조회와 소속, 예매 기간·구매 한도 방향/경계 |
| TicketSeatMappingTest | 10 | 회차별 가격·소유자 상태 조회, 좌석·배치·포인터·가격 제약 |
| TicketPurchaseLimitMappingTest | 6 | 수량과 사용자/회차 범위 조회, UNIQUE·음수·참조 제약 |
| TicketReservationMappingTest | 13 | 해시·NULL·checkout 필드, 키/주문 제약, 승인 시도 저장·부분 필드/기한/시도 중복 제약 |
| SeatHoldMappingTest | 4 | 선점 이력·해제 시각·새 소유자, 회차와 가격 제약 |
| TicketMappingTest | 6 | UUID·사용/취소 이력·새 예약 발급, 중복 번호/발급·회차·가격 제약 |

현재 합계 56개 실행 사례, 테스트 선언 40개다. 기존 정리 당시에는 66개에서 50개 실행 사례, 50개에서 37개 선언으로 줄였으며, 이후 승인 시도 매핑 6개 실행 사례·3개 선언을 추가했다.
독립적인 제약은 파라미터화로 공통 준비·검증 코드를 공유하고, 상태 문자열만 바꿔 같은 필드를 읽던 별도 실행은 대표 조회에 합쳤다.
기존 문서의 단계별 실행 건수는 최초 구현 시의 이력이며 현재 구성은 위 표를 따른다.
실제 선점·만료·결제 동작/동시성은 해당 서비스 구현 단계에서 검증한다.

정리 후 backend에서 ./gradlew clean build를 실행해 전체 211개 테스트가 통과했다. 별도 커버리지 측정 도구가 없어 커버리지 수치 보존은 주장하지 않는다. 주요 제약·필드의 assertion 보존을 검토하고 기간/한도 방향 오류 주입도 확인했다.

매핑 테스트는 com.restless.concert.entity와 com.restless.ticketing.entity 패키지에 모아둔다. 테스트 클래스·메서드는 한국어 @DisplayName으로 검증 목적을 표시하고, 파라미터 사례에는 번호와 입력값을 함께 표시한다. 표시 이름·패키지 정리 후 테스트는 사용자 요청에 따라 재실행하지 않았다.

## 구현 현황: checkout 주문 준비

POST /api/ticket-reservations/{reservationId}/checkout을 구현했다. 본인 예약과 현재 소유권·선점 기한을 잠금 아래 확인하고, 최초 진입에서만 공통 주문과 별도 5분 기한을 같은 트랜잭션에 저장한다. 재요청은 기존 주문·최초 기한을 반환한다. 상세는 [API 계약](../../contracts/api/ticketing.md)과 [트랜잭션 설계](transactions.md)를 따른다. PG 승인·결제 시도 생성·티켓팅 Handler·결제 이후 만료는 후속이다. TicketCheckoutServiceTest의 MySQL Testcontainers 테스트 13개가 통과했다. 상세 실행 범위는 [checkout 테스트 검증](transactions.md#checkout-테스트-검증-2026-11-13)을 따른다.

## 단계별 구현: 승인 시도 저장 기반

공통 결제 대상은 `(TICKET, 예약 ID)`로 확정했다. 예약에 수락한 시도 ID·공통 시작 시각·결과 미확정 여부를 저장하고, 기한 내 승인 시작 수락 및 동일 시도 대조 메서드를 추가했다. Handler·실제 승인/발급/실패/불명확/결제 이후 만료/보상 연결은 다음 단계다. 검증 범위는 [Handler 문서](payment-handler.md#단계별-구현-승인-시도-기록-기반-2026-11-13)를 따른다.

## 단계별 구현: 결제 대상 조회·승인 시작 검증

TicketPaymentService가 공통 트랜잭션 안에서 예약 ID 기준 PayableSnapshot 조회와 잠금 후 승인 시작 검증을 수행하도록 구현했다. 수락 시 승인 시도 기록과 주문 PAYMENT_CONFIRMING을 함께 저장한다. Handler 등록과 실제 결과 처리는 다음 단계다. 대상 조회·승인 시작 필수 통합 검증은 기존 checkout fixture 안에서 현재 10개다. 티켓팅 잠금 후 기록 시점으로 기한을 판정하며 기존 OrderService.find/changeStatus만 사용한다. 기록 후 주문 잠금 대기와 기록 전 티켓팅 잠금 대기를 구분한다. 상세는 [Handler 문서](payment-handler.md#단계별-구현-대상-조회승인-시작-검증-2026-11-13)를 따른다.

## 단계별 구현: 승인 성공·티켓 발급

내부 TicketPaymentService.onApproved에서 수락한 시도와 현재 소유권을 확인해 예약/선점 CONFIRMED·좌석 SOLD·구매 수량 이동·ISSUED 티켓 발급·주문 PAID를 같은 트랜잭션으로 처리한다. 중복·동시 성공은 기존 티켓과 수량을 보존한다. 성공 내부 서비스 10개와 기존 준비·매핑을 포함한 집중 39개가 통과했다. 기존 공통 API만 사용하며 Handler 등록·나머지 실패/불명확/만료/보상 처리는 다음 단계다. [발급 문서](ticket-issuance.md#승인-성공-내부-서비스와-발급-상태-2026-11-13)를 따른다.
