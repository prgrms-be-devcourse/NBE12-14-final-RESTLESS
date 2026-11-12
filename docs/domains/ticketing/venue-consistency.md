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
