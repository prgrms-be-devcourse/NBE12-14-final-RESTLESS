# 공연장 소속과 회차 배치 정합성

- 상태: DECIDED (2026-11-12, 담당자 합의).
- 구현: V20261112_1020__ticketing_enforce_integrity_and_hold_request_keys.sql에 복합 UNIQUE/FK 추가. 실제 DB 적용은 환경별 Flyway 실행 결과로 확인한다.
- 범위: 공연·회차·배치·물리 좌석의 공연장 소속 일치와 회차별 판매 좌석의 배치 일치.

## 관계 제약

개별 ID의 존재뿐 아니라 소속을 함께 검사한다. 부모 참조 컬럼에는 명시적 복합 UNIQUE를 먼저 추가한다.

| 자식 컬럼 | 부모 컬럼 |
|---|---|
| venue_seats(section_id, venue_id) | venue_sections(id, venue_id) |
| layout_seats(layout_id, venue_id) | venue_layouts(id, venue_id) |
| layout_seats(venue_seat_id, venue_id) | venue_seats(id, venue_id) |
| performances(concert_id, venue_id) | concerts(id, venue_id) |
| performances(layout_id, venue_id) | venue_layouts(id, venue_id) |
| performance_seats(performance_id, layout_id) | performances(id, layout_id) |

기존 performance_seats(layout_id, venue_seat_id) → layout_seats(layout_id, venue_seat_id) FK와 결합하여,
같은 공연장이면서 해당 회차가 실제 사용하는 배치에 포함된 좌석만 판매 좌석으로 생성할 수 있다.
기존 개별 FK는 유지한다. 새로운 컬럼은 추가하지 않으며 FK의 기본 RESTRICT 동작을 사용한다.
서비스도 먼저 소속을 검사해 도메인 오류로 설명한다. 동시 변경·누락된 경로의 최종 방어선은 DB 제약이다.

## 적용과 기존 데이터

기존 dev 마이그레이션은 유지하고 공통 결제 V20261112_1000 다음에 통합 V20261112_1020으로 적용한다.
적용 전 아래 조회 결과가 없어야 한다. 불일치가 있으면 담당자가 원인을 확인하고 정리한 뒤 적용한다.
MySQL DDL은 파일 전체가 한 번에 롤백되지 않을 수 있으므로 부분 적용 실패 시 Flyway 상태와 실제 제약을 확인하고 복구한다.

```sql
SELECT c.* FROM venue_seats c
JOIN venue_sections p ON c.section_id = p.id
WHERE c.venue_id <> p.venue_id;

SELECT c.* FROM layout_seats c
JOIN venue_layouts p ON c.layout_id = p.id
WHERE c.venue_id <> p.venue_id;

SELECT c.* FROM layout_seats c
JOIN venue_seats p ON c.venue_seat_id = p.id
WHERE c.venue_id <> p.venue_id;

SELECT c.* FROM performances c
JOIN concerts p ON c.concert_id = p.id
WHERE c.venue_id <> p.venue_id;

SELECT c.* FROM performances c
JOIN venue_layouts p ON c.layout_id = p.id
WHERE c.venue_id <> p.venue_id;

SELECT c.* FROM performance_seats c
JOIN performances p ON c.performance_id = p.id
WHERE c.layout_id <> p.layout_id;

```

## 보장 범위와 후속 정책

이 제약은 소속 일치만 보장한다. 배치 게시 여부·좌석 판매 가능 여부와 아래 권한·시간 정책은 서비스에서 별도로 검증한다.
예약·선점·티켓의 회차 일치와 현재 소유 선점의 좌석 일치는 별도 [관계 정합성 계약](reservation-seat-consistency.md)에 따라 V20261112_1020에 정의한다.

## 검증 계획 (미실행)

- 동일 공연장의 정상 연결과 동일 배치를 공유하는 여러 회차의 판매 좌석 생성 허용.
- 다른 공연장 구역·물리 좌석·배치·공연 연결 거절.
- 같은 공연장의 다른 배치로 회차별 판매 좌석을 연결하면 거절.
- 기존 판매 좌석과 맞지 않는 회차 layout_id 변경 거절.
- 기존 불일치 데이터가 있으면 신규 FK 적용 거절, 자동 데이터 수정 없음.

## MVP 배치 등록·선택과 변경 제한 (DECIDED, 2026-11-12)

- 운영자가 공연장과 좌석 배치를 사전 등록한다. MVP에서 배치는 초기 데이터로 등록하며, 별도 배치 관리자 화면이나 주최자용 자유 좌석 배치 편집기는 만들지 않는다.
- 주최자는 공연 회차 등록 시 해당 공연장의 사전 등록된 배치 중 하나를 선택한다.
- 예매 오픈 시각과 같거나 이후에는 회차의 배치 변경을 금지한다. 예약이 없거나 모두 취소됐더라도 변경할 수 없다. 운영자도 일반 변경 흐름에서 이 제한을 우회하지 않는다.
- 선택된 배치 원본의 구성·좌표 등을 수정해 오픈된 회차의 배치가 간접 변경되는 것도 허용하지 않는다.
- 이 제한은 FK만으로 보장되지 않는다. 배치 변경 처리에서 회차 잠금 후 서버/DB 기준 현재 시각과 예매 오픈 시각을 재검증해야 한다.
- 코드·추가 마이그레이션은 이번 정책 문서 반영에 포함하지 않는다.

아직 확정하지 않은 사항:

- 초기 데이터의 구체적인 적재 방식·실행 절차와 공통 권한 enum 매핑.
- 미등록 배치 자료의 실제 전달 채널·검토 담당자와 추가 등록 실행 절차.
- 오픈 전 배치 교체 시 판매 좌석 재생성·가격 재검증·기존 예약 이력 제한.
- 게시된 배치 전체의 불변 버전 정책과 긴급 운영 변경·공연 취소 절차.

추가 검증 계획 (미실행):

- 주최자가 등록되지 않은 배치나 다른 공연장의 배치를 선택하면 거절.
- 예매 오픈 정각·이후 배치 변경 거절, 예약이 없어도 동일 적용.
- 공유 배치 원본 수정으로 오픈된 다른 회차의 배치가 바뀌지 않음.

## 배치 좌석 위치 서버 캐시 (2026-11-27)

- 고객 좌석 조회(`GET /api/performances/{performanceId}/seats`)는 배치 좌석 위치(구역·열·번호·좌표)를 서버마다 메모리에 배치 ID별로 기억한다(`LayoutSeatPositionCache`). 위 변경 제한과 앱에 배치 좌석 쓰기 경로가 없다는 점을 전제로 한다.
- 유효 시간은 10분(`restless.concert.layout-cache.ttl`)이다. 오픈 전 배치를 DB에서 직접 고친 경우 서버마다 최대 10분 동안 이전 위치가 보일 수 있다. 서버 간 무효화는 하지 않는다. `restless.concert.layout-cache.enabled=false`로 끌 수 있다.
- 배치 좌석 위치 캐시는 회차 존재·공개 확인, 판매 좌석 상태·등급·가격을 담지 않는다. 다만 `/seats` 응답 전체는 회차별로 최대 0.5초 공유한다([ADR-0008](../../decisions/0008-ticketing-seat-response-cache.md), #542). `/seats/status` 응답도 회차별로 최대 0.5초 공유한다([ADR-0009](../../decisions/0009-ticketing-seat-status-response-cache.md), #562). 선점의 판매 가능 좌석은 요청마다 DB에서 읽는다.
- 선점은 배치 좌석 전체가 아니라 잠근 좌석의 물리 좌석 ID로 판매 가능 여부만 조회한다(#510). 판매 불가 좌석이 하나라도 있으면 전체 선점을 `SEAT_UNAVAILABLE`로 거절하는 규칙은 같다.
- 같은 배치를 동시에 처음 찾는 요청은 DB를 한 번만 읽는다. 읽기 실패는 기억하지 않는다. 판매 좌석과 배치가 맞지 않으면 기존처럼 오류로 끝낸다.

## 미등록 배치 접수·등록 (DECIDED, 2026-11-12)

1. 주최자가 원하는 배치 자료를 운영자에게 별도로 전달한다. MVP에서는 요청 접수 화면을 만들지 않는다.
2. 자료는 PDF·이미지·표 등으로 무대 위치, 구역·행·좌석 번호, 판매·판매 제외 좌석을 확인할 수 있어야 한다.
3. 운영자가 실제 공연장·좌석 정보와 자료의 정합성을 검토한 뒤 서비스의 배치 데이터로 등록한다.
4. 주최자는 등록이 완료된 배치를 회차에서 선택한다. 자료 제출만으로 자동 등록·선택되지는 않는다.

파일 업로드·이미지 분석을 통한 좌석 자동 생성 기능은 MVP에 포함하지 않는다.
추가 배치는 기존 배치와 이력을 덮어쓰지 않도록 별도 배치/버전으로 등록한다. 초기 적재와 추가 등록의 구체적인 스크립트·절차는 후속 구현 대상이다.
새 배치가 등록돼도 이미 예매 오픈된 회차의 배치를 교체할 수 없다.
이번 변경은 정책 문서만 반영하며 초기 데이터 파일·적재 코드·마이그레이션은 추가하지 않는다.

## 구현 현황: 공연장 기본 엔티티

- concert 모듈에 Venue/venues, VenueSection/venue_sections, VenueSeat/venue_seats JPA 매핑을 구현했다.
- 공연장·구역 관계는 venueId/sectionId 스칼라 ID로 참조한다. 실제 소속 일치는 기존 복합 FK가 최종 검증한다.
- 기존 BIGINT IDENTITY, 문자열 길이·NOT NULL, 접근성 boolean, UTC created_at을 유지하며 마이그레이션은 변경하지 않는다.
- status는 기존 VARCHAR 컬럼에 맞춰 문자열로 매핑한다. 새 상태값 집합이나 상태 변경 정책은 이번 단계에서 확정하지 않는다.
- VenueMappingTest는 MySQL Testcontainers와 Flyway, ddl-auto=validate를 사용한다. 저장 후 영속성 컨텍스트를 비우고 조회하며 UTC 마이크로초·접근성 값을 확인한다.
- 다른 공연장 구역 연결, 같은 공연장 내 구역 코드 중복, 같은 구역 내 좌석 위치 중복 거절까지 4개 테스트 실행을 확인했다.
- 등록 API·서비스는 후속 단계다. 배치·회차 매핑은 아래 구현 현황을 참고한다.

## 구현 현황: 배치 엔티티

- VenueLayout/venue_layouts와 LayoutSeat/layout_seats를 JPA로 매핑했다. 기존 Flyway는 변경하지 않는다.
- LayoutSeatId를 @Embeddable로 두고 (layout_id, venue_seat_id)를 @EmbeddedId로 매핑한다. 복합 키는 두 ID로 equals/hashCode를 구현하며 별도 자동 증가 ID는 만들지 않는다.
- venueId 등 관계는 기존 단계와 같이 스칼라 ID로 참조한다. 배치·실제 좌석의 소속 일치는 DB 복합 FK로 검증한다.
- 배치 version은 일반 버전 번호이며 JPA 낙관적 락의 @Version이 아니다.
- published_at은 기존 NOT NULL DATETIME(6)에 맞춰 UTC Instant로 매핑한다. 좌표는 DECIMAL(10,2)에 맞춰 BigDecimal, 판매 가능 여부는 boolean으로 매핑한다.
- 관리 status는 문자열로 유지한다. 테스트의 PUBLISHED 값은 예시이며 게시·수정·회차 선택 정책을 구현한 것은 아니다.
- VenueLayoutMappingTest 5개로 영속성 컨텍스트 초기화 후 복합 키 조회, 배치 버전별 같은 물리 좌석 재사용, 좌표·표시명·판매 여부 보존, 서로 다른 공연장 연결 두 경로 거절, 배치 코드/버전 중복과 비양수 버전 거절을 확인했다.
- 기존 VenueMappingTest와 함께 ./gradlew test --tests 'com.restless.concert.*' 실행으로 총 9개 테스트가 통과했다.

## 구현 현황: 공연·회차 엔티티

- Concert는 concerts, Performance는 performances에 매핑한다. 회차의 Java 이름을 Performance로 통일하고 Flyway 테이블명 performances는 유지한다.
- 공연은 venueId/sellerId, 회차는 concertId/venueId/layoutId를 스칼라 ID로 참조한다. 소속 일치는 기존 복합 FK가 최종 검증한다.
- BIGINT IDENTITY, NOT NULL, 문자열 길이, UTC Instant/DATETIME(6) 시각을 기존 스키마에 맞췄다. 신규 마이그레이션은 없다.
- status는 문자열로 유지한다. 테스트의 SCHEDULED는 저장·조회 예시이며 상태값 집합을 새로 확정하지 않는다.
- ConcertPerformanceMappingTest 8개는 저장 후 영속성 컨텍스트를 비우고 여러 회차를 조회하며, 생성·시작·예매 시각의 마이크로초를 확인한다.
- 서로 다른 공연장 소속 공연/배치 연결과 잘못된 예매 기간·비양수 구매 한도가 기존 DB 제약으로 거절되는지 검증한다.
- ./gradlew clean test --tests 'com.restless.concert.*' 실행으로 공연 도메인 테스트 총 17개가 통과했다. 최초 실행의 클래스 로딩 오류는 빌드 산출물 초기화 후 재실행에서 해소됐다.
- 주최자 권한, 회차 등록 API, 오픈 후 배치 변경 제한의 서비스 처리, 판매 좌석·예매 로직은 이번 매핑 구현 범위가 아니다.

회차 내부 명칭은 Performance/PerformanceRepository로 통일하고 테스트도 ConcertPerformanceMappingTest로 변경했다. DB performances는 유지한다. 좌석 조회 API는 후속 결정에 따라 /api/performances/{performanceId}/seats로 통일했다. 선점 요청 본문의 회차 식별자도 performanceId로 통일하고 요청 DTO·POST API를 구현했다. 내부 이름 변경 당시 ./gradlew build 실행으로 전체 테스트 161개가 통과했으며, 후속 API 명명 변경·선점 구현에 대한 테스트는 아직 실행하지 않았다.

## 구현 현황: 공연 상세·회차 목록 조회

- ConcertController에서 비로그인 공연 상세와 회차 목록 GET을 제공한다. 회차 명칭은 Performance로 통일하며 목록 경로는 /api/concerts/{concertId}/performances다.
- ConcertQueryService가 공연 존재 여부를 먼저 확인한다. 없는 공연은 404, 회차가 없는 기존 공연은 빈 목록을 반환한다.
- 해당 concertId의 회차만 시작 시각 오름차순으로 조회한다. 상세·회차 결과는 application/dto로 변환하며 엔티티·Repository는 모듈 밖에 노출하지 않는다.
- 공연·회차의 상태값 집합과 공개 상태 필터는 임의로 정의하지 않는다. 조회는 저장된 상태를 반환하며 데이터·소유권을 변경하지 않는다.
- ConcertPerformanceMappingTest의 기존 Repository 조회 검증을 비로그인 API 검증으로 교체하고 상세·회차 목록의 없는 공연 404를 추가했다. 테스트는 아직 실행하지 않았다.

## 구현 현황: 공연 목록 조회

- GET /api/concerts는 비로그인 페이지 조회를 제공한다. page 기본 0, size 기본 20·최대 100이며 생성 시각·ID 내림차순으로 정렬한다.
- ConcertQueryService는 기존 ConcertRepository의 페이지 조회를 사용한다. 응답은 application/dto의 ConcertPage로 만들고 항목은 ConcertDetail을 재사용한다.
- 공연이 없거나 범위를 벗어난 페이지는 빈 content를 반환한다. 상태별 공개 필터·검색은 이번 범위에 포함하지 않으며 좌석·예약·수량은 변경하지 않는다.
- 기존 ConcertPerformanceMappingTest의 환경을 재사용해 목록·정렬·페이지 및 잘못된 입력 테스트를 작성했으며 아직 실행하지 않았다.

## 좌석 데이터 준비와 회차별 판매 좌석 생성 계획 (2026-11-16 기록)

- 당시 상태: DEFERRED. 이후 회차 등록과 구역별 가격 설정·판매 좌석 생성은 아래 구현 기록대로 추가했다. 공연장·배치 초기 등록 절차는 후속이다.
- 운영자용 공연장·구역·물리 좌석·배치 초기 데이터와 적재 절차를 준비한다. 배치에는 화면 좌표·표시명·판매 가능 여부를 포함한다.
- 주최자가 회차를 등록할 때 같은 공연장의 사전 등록 배치를 선택하도록 연결한다.
- 선택한 배치의 판매 가능 좌석에 등급·가격을 지정해 회차별 performance_seats를 AVAILABLE/current_hold_id=NULL로 일괄 생성한다. 동일 회차·물리 좌석 중복 생성 방지와 전체 생성의 원자성을 검증한다.
- 공연장·회차·배치·좌석의 소속 일치 및 예매 오픈 이후 배치 변경 금지 정책은 유지한다. 오픈 전 교체 조건과 등록 권한·실행 절차의 미확정 사항은 구현 전에 정리한다.
- 자유 좌석 배치 편집기와 배치 관리자 화면은 기존 MVP 제외 정책을 유지한다.
- 회차 가격 설정은 구역별 동일 가격을 적용한다. 장애인석을 포함한 개별 좌석 예외 가격은 지원하지 않는다 (2026-11-24 사용자 결정).

기존 좌석 데이터가 있는 환경에서의 예매·결제 연결과는 분리해 진행할 수 있다. 이 계획 작성 당시에는 생성 API·초기 데이터 적재가 구현되지 않았다.

로컬 수동 확인용 샘플은 [backend/scripts/seed/ticketing](../../../backend/scripts/seed/ticketing/README.md)에 별도로 제공한다. 가상 공연장·배치·회차·판매 좌석 100석을 추가하는 개발용 시드이며, 위 운영자 등록 절차·주최자 생성 API의 구현을 뜻하지 않는다.

## 주최자 정책과의 관계

[주최자 정책](organizer-policy.md)(2026-11-26 개정)이 의도한 동작의 정본이다. 공연 등록 시 공연장·배치를 함께 선택하고 모든 회차가 같은 배치와 공연 공통 구역별 등급·가격을 쓴다. 기존 공연은 공개 상태를 유지하며 회차별 배치·가격이 다른 공연은 자동 통일하지 않는다([전환 정책](organizer-policy.md#10-기존-데이터-전환)).

아래 절은 작성 당시 구현의 기록이다. 즉시 공개·회차별 가격 등 정본과 다른 부분은 [구현 계약](organizer-implementation.md)의 현재 구현을 따른다.

## 주최자 공연 등록 (2026-11-24)


활성 ORGANIZER 또는 ADMIN은 기존 공연장과 제목으로 공연을 등록할 수 있다. 등록한 회원을 seller_id로 기록하며 SCHEDULED 상태로 즉시 공개 조회된다. 관리 조회는 본인 공연 또는 ADMIN에게 허용하며 타인 공연은 없는 공연과 같이 404다. 상태별 공연장 선택 제한과 비공개 초안은 아직 정하지 않았다. 공연 등록과 별도로 회차·배치 선택·가격 설정을 수행한다.

공연 등록 시 설명·시작일과 종료일·소요 시간도 선택 입력할 수 있다. 공연장 이름과 주소는 선택한 기존 공연장 정보를 사용한다. 기존 데이터는 추가 정보가 null일 수 있다.

## 주최자 회차 등록 (2026-11-24)

활성 주최자와 관리자는 각자 자신이 등록한 공연에만 회차를 추가한다. 공연 기간이 없는 경우 등록을 거절한다. 회차 시작 시각을 공연장 시간대로 변환한 날짜가 기간 시작일 이상·종료일 이하여야 한다. 예매 시작 < 예매 마감 ≤ 회차 시작, 구매 한도 양수, 같은 공연장에 등록된 배치를 확인한다. 회차는 SCHEDULED로 생성해 공개 조회되며 가격 설정 전에는 판매 좌석이 없다. 기존 공연의 기간 수정은 별도 구현이 필요하다.

## 구역별 가격 설정과 판매 좌석 생성 (2026-11-24)



주최자는 본인 공연장의 등록된 배치와 판매 가능 구역별 좌석 수를 조회한 뒤 회차 등록에 배치를 선택한다. 선택한 배치의 판매 가능한 모든 구역에 등급과 가격을 입력하면 회차별 판매 좌석을 같은 트랜잭션에서 생성한다. 장애인석 등 개별 예외는 없다. 판매 제외 좌석은 생성하지 않는다.

회차 잠금은 동일 회차 설정을 직렬화하며, 기존 판매 좌석 변경은 좌석 ID 오름차순 잠금 뒤 최초 예매 시작 전과 선점 이력·현재 점유 여부를 확인한다. 이미 존재하는 좌석과 배치가 일치하지 않으면 수정하지 않는다. 같은 설정의 재요청은 예매 시작 후에도 기존 결과를 반환하며 상태를 바꾸지 않는다. 최초 설정은 예매 시작 뒤에도 가능하지만 변경은 허용하지 않는다. 가격 0원은 저장 가능하지만 티켓팅 무료 결제 확정은 별도 범위다.

## 주최자 공연·회차 정보 수정 (2026-11-24)





본인 공연의 제목과 소개는 예매 상태와 관계없이 수정할 수 있다. 공연 기간·소요 시간은 해당 공연의 모든 회차가 예매 시작 전이고 취소·만료를 포함한 선점 이력이 없어야 변경할 수 있으며, 새 기간은 모든 회차를 포함한다. 회차 시작·예매 시간과 구매 한도 변경은 해당 회차의 기존·새 예매 시작 시각이 모두 DB 현재 시각보다 미래이고 선점 이력이 없을 때 허용한다. 변경 없는 회차 재요청은 예매 시작 후에도 허용한다. 공연장과 배치는 수정 API 범위가 아니다.

공연 수정과 회차 생성·수정은 공연 행을 먼저 잠그고, 회차가 필요하면 회차 행을 잠근다. 선점 트랜잭션은 회차를 공유 잠금으로 읽고 수정은 쓰기 잠금을 사용한다. 여러 회차를 잠글 때는 ID 오름차순으로 잠근다. 이 잠금은 공연 기간 검증과 선점 시 사용하는 회차 설정이 동시 수정에 의해 달라지지 않도록 한다.
