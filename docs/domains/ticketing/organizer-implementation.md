# 주최자 MVP 구현 계약 초안

상태: DRAFT (2026-11-24). 정책은 [주최자 정책](organizer-policy.md)을 따른다. 코드·DB 적용 전 설계이며 구현 완료가 아니다.

## 로컬 데이터 사전 확인

2026-11-24 restless-mysql-1의 restless DB를 SELECT로 확인했다. 공연 2개, 한 공연의 여러 배치 사용 0건, 0원 판매 좌석 0건이다. duration_minutes 컬럼이 없어 소요 시간 누락 쿼리는 실행 오류로 미확인이다. 이 결과는 로컬 DB에 한정하며 운영 데이터 확인을 대신하지 않는다. 구역별 가격 불일치와 타 도메인 참조 현황은 추가 점검해야 한다.

## 스키마 적용 순서

1. 기존 Flyway 이력과 작업 트리의 V20261124_1000 상세 정보 컬럼 적용 여부를 확인한다. 이미 머지한 migration은 수정하지 않는다.
2. 새 migration으로 concerts.is_published(FALSE 기본), published_at(NULL 가능), layout_id(NULL 가능)를 추가한다. 기존 공연만 공개 TRUE로 채우고 실제 공개 시각은 NULL 유지한다.
3. concert_section_prices를 생성한다. PK(concert_id, section_label), grade VARCHAR(20), price BIGINT CHECK(price>0), concerts FK를 사용한다. 구역 이름의 비교 규칙은 기존 layout_seats와 동일하게 유지한다. 서비스에서 선택 배치의 판매 구역 전체를 정확히 검증한다.
4. 기존 공연의 layout_id는 회차 배치가 하나인 경우만 이관 후보로 삼는다. 회차 없음·배치/가격 불일치·판매 좌석 누락은 자동 가격 복사 없이 검토한다. 기존 회차·좌석·거래 스냅샷은 보존한다.
5. 신규 공연의 배치 필수·소속 검증은 서비스에서 강제한다. 기존 불일치 때문에 전체 회차에 새 복합 FK를 즉시 강제하지 않는다. 기존 performance_seats의 price>=0 CHECK는 기존 데이터 보존을 위해 유지하고 신규 생성 경로에서 1원 이상을 강제한다.

## 요청·응답

- 공연 생성: 기존 필드 + 필수 layoutId. 성공 201, 관리 응답에 layoutId, isPublished, publishedAt, pricingConfigured를 추가한다. 기존 고객 응답 필드는 보존한다.
- 공통 가격 PUT /api/organizer/concerts/{concertId}/section-prices: sections[{sectionLabel,grade,price}] 전체 교체. 회차 없고 미공개인 경우만 허용. 성공 200, 설정한 구역 목록 반환.
- 개별 회차 생성: startsAt, bookingOpensAt, bookingClosesAt, maxTicketsPerUser. layoutId·가격은 입력받지 않는다. 공연 설정으로 회차·좌석 생성 후 201 반환.
- 일괄 POST .../performances/batch: bookingOpensAt, maxTicketsPerUser, performances[{startsAt,bookingClosesAt}]. 1~10개. 201 {performances:[회차 정보]}, 전체 롤백. 날짜는 Instant의 ISO-8601 오프셋 포함 형식, 화면에서 공연장 시간대로 표현한다.
- 공개 POST .../publish: 본문 없음. 성공 200 관리 상세 반환. 이미 공개된 요청은 인증·권한 확인 후 현재 상세를 반환하고 최초 공개 시각을 덮어쓰지 않는다.
- 초안 삭제 DELETE 공연/회차 경로: 성공 204. 이력·참조가 있으면 409. 권한 없는 타인 대상은 기존 규칙대로 404.
- 입력 오류는 400 INVALID_REQUEST, 미인증 401, 역할 부족 403, 없는/접근 불가 대상 404. 미공개 위반·가격 미완료·회차 충돌·연결 참조는 409의 구체적 ErrorCode를 구현 시 기존 enum과 맞춰 추가한다.

## 트랜잭션과 경계

공연 준비 변경은 공연 행 → 회차 ID 오름차순 → 판매 좌석 ID 오름차순으로 잠근다. 생성·가격 변경·배치 변경·공개·삭제는 같은 공연 행을 잠가 순서를 보장한다. 공연 공개 판정은 concert application 계약으로 노출한다. ticketing이 공연 repository에 접근하지 않는다.

회차 생성과 좌석 생성은 같은 DB 트랜잭션의 동기 application 호출로 처리한다. 이벤트 리스너로 좌석 생성을 넘기지 않는다. 가격 완전성 조회·좌석 생성·이력 확인은 ticketing 계약으로 제공한다. PG 호출을 포함하지 않는다.

드롭·경매 참조 검사는 각 도메인의 계약과 DB FK로 보호한다. 검사 직후 연결이 생겨 FK가 삭제를 거절하는 경우도 409로 처리하며 타 도메인 삭제를 수행하지 않는다.

## 다음 구현 단위

공개 상태·공통 설정 스키마와 엔티티 → 초안 조회/선점 차단 및 기본 권한 → 공통 가격·공연 편집 → 회차 생성/좌석 원자 생성 및 중복·겹침 → 전체 공개/공개 후 변경 차단 → 조건부 삭제 → 최소 UI 순으로 진행한다. 상세 테스트는 해당 변경 단위에서 기존 focused 테스트를 재사용하고 경쟁·롤백 사례를 검증한다.

## 구현 기록 (2026-11-24)

로컬 Flyway 최신 성공 버전은 20261122.1401이며 V20261124_1000은 미적용이었다. V20261124_1100에는 상세 정보, 공개 표식·시각·공통 layout_id와 concert_section_prices 구조를 포함했다. 기존 DB에는 적용하지 않았다. 기존 공연은 공개 상태를 유지하고 신규 공연은 초안으로 생성한다. 공연 공통 배치와 공통 구역 가격 설정 API를 연결했으며, 회차 생성은 공통 배치·가격을 사용해 회차와 판매 좌석을 한 트랜잭션에서 생성한다. 고객 목록·상세·회차·좌석·예약 가능 수량·선점 경로는 공개 여부를 확인한다. 기존 회차의 배치·가격은 자동 이관하지 않는다.

단건·일괄 회차 등록, 공연장·배치 변경, 초안 고객 접근 차단, 공연 전체 공개와 조건부 삭제를 구현했다. 선택 공개·재공개와 비공개 전환은 후순위다. 기존 공개 공연 중 회차별 배치·가격이 다른 공연은 별도 확인 대상으로 유지한다.

Focused 테스트 통과: `./gradlew test --tests 'com.restless.concert.OrganizerConcertApiTest' --tests 'com.restless.ticketing.TicketSeatQueryApiTest' --tests 'com.restless.ticketing.TicketQueryApiTest'` (해당 시점 24 tests, BUILD SUCCESSFUL). 전체 빌드와 mutation gate는 실행하지 않았다.

## 정책·경계 점검 (2026-11-24)

관리자는 전체 공연의 관리 조회와 단건 회차·공통 가격 작업을 수행한다. 공개 공연의 일정 변경과 회차 추가를 막고, 초안 일정은 선점·예약 이력이 없을 때 미래 예매 시작 시각으로 복구할 수 있다. 회차 시간은 공연 단위 잠금 아래 소요 시간 기준으로 비교하며, 시간 간격은 시작 포함·종료 제외다. 기간·소요 시간 변경은 회차 시작 시간순으로 겹침을 검사한다. 좌석 초기화는 concert의 application 계약을 ticketing이 구현하며 회차 생성과 같은 트랜잭션에 참여한다. 공통 가격 교체는 삭제를 먼저 flush해 유일 제약 충돌을 방지한다.

공연장·배치 변경, 일괄 등록 및 조건부 삭제를 구현했다. 기존 공개 공연의 배치·가격 불일치는 별도 검토가 필요하다.

공연 전체 공개는 공연 행과 회차 행을 잠그고 모든 공통 설정·회차 일정·판매 좌석 일치 여부를 확인한 뒤 DB 현재 시각 기준으로 모든 예매 시작이 미래인지 검사한다. ticketing application 계약이 회차별 판매 좌석과 공연 공통 가격을 비교한다. 공연의 공개 표식 하나로 전체 회차에 동일하게 적용한다. 재요청은 기존 공개 시각을 보존한다.

공개 기능 focused 테스트: `./gradlew test --tests 'com.restless.concert.OrganizerConcertApiTest' --tests 'com.restless.ticketing.TicketSeatQueryApiTest'` — 25건 통과. 공개와 회차 추가의 경합을 포함한다. 전체 빌드와 mutation gate는 실행하지 않았다.

정책·경계 점검 후 `./gradlew test --tests 'com.restless.concert.OrganizerConcertApiTest' --tests 'com.restless.ticketing.TicketSeatQueryApiTest' --tests 'com.restless.ticketing.TicketQueryApiTest'`: 27 tests, BUILD SUCCESSFUL.

추가 회귀 검증: `./gradlew test --tests 'com.restless.concert.OrganizerConcertApiTest'` 15건 통과. `./gradlew test --tests 'com.restless.ticketing.application.TicketReservationServiceTest.concurrentUsersCannotOwnTheSameSeat' --tests 'com.restless.ticketing.application.TicketReservationServiceTest.replayPreservesOriginalReservationAndRejectsDifferentContent' --tests 'com.restless.ticketing.application.TicketReservationServiceTest.concurrentSameKeyRequestsReturnOneReservation'` 3건 통과. 전체 빌드와 mutation gate는 실행하지 않았다.


단건 회차 등록은 READ_COMMITTED로 실행한다. 기본 REPEATABLE READ에서는 공연 행 잠금 전에 회원 조회로 읽기 스냅샷이 고정돼, 동시에 커밋된 같은 시각 회차를 겹침 검사에서 보지 못하고 두 회차가 모두 생성됐다(`concurrentSingleCreationsCannotOverlap` 수정 전 실패 [201, 201]).

V20261125_1000은 `concerts.is_published` 기본값을 TRUE로 바꾼다. 주최자 API 생성은 엔티티가 FALSE를 명시하므로 계속 초안이다. 공개 여부를 지정하지 않은 직접 INSERT는 공개로 저장된다(`directlyInsertedConcertDefaultsToPublished`). 기존 V20261124_1100은 수정하지 않았다.

PR 분할(2026-11-25): 정책 문서(#413) → 공개 표식·고객 노출 차단 → 공연 초안·공통 가격 → 회차·판매 좌석·전체 공개(#401) 순으로 쌓았다. 정리 사항: 빈 `OrganizerSeatPricingController`와 호출되지 않던 회차별 가격 설정 `OrganizerSeatPricingService`(관련 `OrganizerPerformanceService.lockOwnedSeatLayout`·`ensureSeatPricingAllowed`, `TicketSeat.changePricing`, `TICKET_PRICING_*` 오류 코드와 이를 직접 호출하던 테스트 3개 포함) 삭제, 쓰이지 않는 `countBySellerIdAndPublishedTrue`와 `LayoutSeatRepository`의 application DTO import 삭제, DB 기본값 공개 전환으로 불필요해진 드롭·공통 테스트 fixture의 공개 표식 복원. `TicketCheckoutServiceTest`의 정리 쿼리는 같은 공연장의 모든 회차로 넓힌 것이며(가격 테스트가 새 회차를 만듦) 기존 정리 대상을 모두 포함한다.

## 일괄 등록, 공연장·배치 변경, 조건부 삭제

회차 일괄 등록은 공연 행 잠금과 하나의 READ_COMMITTED 트랜잭션에서 회차 1~10개와 판매 좌석을 만든다. 단건 등록 메서드를 내부 호출하며 하나라도 실패하면 전체를 롤백하고, 검증 오류에 `details.performanceIndex`(0부터)를 담는다. 초안의 공연장·배치를 실제로 바꾸면 공통 가격을 삭제하고, 같은 값 재제출은 가격을 유지한다. 공개된 공연의 회차 수정은 회차 행을 잠그지 않고 400으로 거절해 진행 중인 선점을 기다리지 않는다(`publishedPerformanceUpdateDoesNotWaitForHolds`).

회차 삭제는 공개 이력과 선점·예약 이력(취소·만료 포함)이 없을 때 판매 좌석을 먼저 지운 뒤 회차를 지운다. 좌석 상태 불일치와 FK 위반은 409 `CONCERT_DELETE_CONFLICT`다. 공연 삭제는 공개 이력과 회차가 없을 때 공통 가격과 공연을 지운다. 드롭·경매 참조는 각 테이블의 공연 FK로 막고 드롭·경매 코드는 바꾸지 않는다(2026-11-25 결정). 커밋 전 드롭 INSERT가 있으면 FK 공유 잠금 때문에 공연 행 잠금에서 기다린 뒤 FK 위반으로 409가 된다(`concertDeletionWaitsForUncommittedDropReference`).
