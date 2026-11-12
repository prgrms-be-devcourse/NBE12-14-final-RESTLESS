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
| TicketReservationMappingTest | 7 | 해시 방어적 복사·NULL·checkout 필드, 키 범위/대소문자·주문 제약 |
| SeatHoldMappingTest | 4 | 선점 이력·해제 시각·새 소유자, 회차와 가격 제약 |
| TicketMappingTest | 6 | UUID·사용/취소 이력·새 예약 발급, 중복 번호/발급·회차·가격 제약 |

합계 50개 실행 사례(기존 66개), 테스트 선언 37개(기존 50개)다.
독립적인 제약은 파라미터화로 공통 준비·검증 코드를 공유하고, 상태 문자열만 바꿔 같은 필드를 읽던 별도 실행은 대표 조회에 합쳤다.
기존 문서의 단계별 실행 건수는 최초 구현 시의 이력이며 현재 구성은 위 표를 따른다.
실제 선점·만료·결제 동작/동시성은 해당 서비스 구현 단계에서 검증한다.

정리 후 backend에서 ./gradlew clean build를 실행해 전체 211개 테스트가 통과했다. 별도 커버리지 측정 도구가 없어 커버리지 수치 보존은 주장하지 않는다. 주요 제약·필드의 assertion 보존을 검토하고 기간/한도 방향 오류 주입도 확인했다.
