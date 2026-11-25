# 티켓팅 주최자 구현 계약

상태: 현재 구현 기록(dev, 2026-11-26)과 [주최자 정책](organizer-policy.md) 2026-11-26 개정에 따른 구현 예정 사항이다. 요청·응답은 [API 계약](../../contracts/api/ticketing.md)을 따른다.

## 1. 현재 구현

### 스키마

- `V20261124_1100`: `concerts`에 `description`, `period_start_date`, `period_end_date`, `duration_minutes`, `is_published`, `published_at`, `layout_id`(FK)를 추가했다. 기존 공연은 `is_published = TRUE`, `published_at = NULL`로 채웠다. 공통 가격 테이블 `concert_section_prices(concert_id, section_label, grade, price)`를 만들었다. PK는 `(concert_id, section_label)`이고 `price > 0` CHECK와 공연 FK가 있다.
- `V20261125_1000`: `is_published` DB 기본값을 TRUE로 바꿨다. 주최자 API는 엔티티가 FALSE를 명시해 초안을 만든다. 공개 여부를 지정하지 않은 직접 INSERT(다른 도메인 fixture 등)는 공개로 저장된다.

### 기능

| 기능 | 구현 |
|---|---|
| 공연 초안 생성·관리 조회 | ORGANIZER는 본인, ADMIN은 전체. 주최자 API로 만든 공연은 비공개 초안 |
| 공통 구역 가격 | 공개 전·회차 없음일 때 배치의 판매 가능 구역 전체를 교체. 삭제를 먼저 flush해 유일 제약 충돌을 피한다 |
| 공연장·배치 변경 | 공개 이력·회차 없음일 때. 실제로 바뀌면 공통 가격 삭제, 같은 값은 유지 |
| 회차 등록 | 단건·일괄(1~10개). 공통 배치·가격으로 판매 좌석을 같은 트랜잭션에서 생성. 일괄 검증 오류는 `details.performanceIndex`(0부터) |
| 회차 수정 | 공개 전, 선점·예약 이력이 없을 때 일정·구매 한도 수정 |
| 공개 | 공연과 모든 회차를 한 번에 공개. 모든 회차의 예매 시작이 DB 현재 시각보다 미래여야 한다. 재요청은 최초 공개 시각을 보존 |
| 고객 노출 | 고객 목록·상세·회차·좌석·예약 가능 수량·선점이 공개 여부를 확인. 결제 복구·티켓 조회는 공개 표식으로 막지 않는다 |
| 회차 삭제 | 공개 이력과 선점·예약 이력이 없을 때 판매 좌석을 지운 뒤 회차를 물리 삭제. 좌석 상태 불일치·FK 위반은 409 `CONCERT_DELETE_CONFLICT` |
| 공연 삭제 | 공개 이력과 회차가 없을 때 공통 가격과 공연을 물리 삭제. 드롭·경매 참조는 각 테이블의 공연 FK 위반으로 409 |

### 잠금과 트랜잭션

- 공연 준비 변경(가격·배치·회차 생성·공개·삭제)은 공연 행 `PESSIMISTIC_WRITE`로 직렬화한다. 순서는 공연 행 → 회차 ID 오름차순 → 판매 좌석 ID 오름차순이다.
- 회차 등록은 READ_COMMITTED로 실행한다. 기본 REPEATABLE READ에서는 공연 행 잠금 전에 읽기 스냅샷이 고정돼, 동시에 커밋된 같은 시각 회차를 겹침 검사가 보지 못했다(`concurrentSingleCreationsCannotOverlap`).
- 선점은 회차 행에 `PESSIMISTIC_READ`(`PerformanceRepository.findSharedById`)를 건다. 공개된 공연의 회차 수정 요청은 회차 행을 잠그지 않고 400으로 거절해 진행 중인 선점을 기다리지 않는다(`publishedPerformanceUpdateDoesNotWaitForHolds`).
- 커밋 전 드롭 INSERT가 있으면 FK 공유 잠금 때문에 공연 삭제가 기다린 뒤 FK 위반으로 409가 된다(`concertDeletionWaitsForUncommittedDropReference`).
- 모듈 경계: concert의 application 인터페이스(`ReservationHistory`, `PerformanceSeatCreator`, `PerformanceSeatReadiness`)를 ticketing이 구현한다. ticketing은 공연 repository에 접근하지 않는다(`OwnedModuleBoundaryTest`).

### 테스트 위치

- `com.restless.concert.OrganizerConcertApiTest`: 주최자 API, 동시 회차 등록, 배치 변경, 삭제와 드롭·경매 참조
- `com.restless.concert.ConcertPublicationVisibilityTest`: 공개 기본값, 고객 노출·선점 차단, 드롭의 공개 공연 확인
- `com.restless.ticketing.application.TicketReservationHistoryTest`: 회차 삭제 시 판매 좌석 정리

## 2. 2026-11-26 정책 개정과의 차이

| 정책 | 현재 구현 | 해야 할 것 |
|---|---|---|
| 예매 오픈은 공연 단위 | 회차마다 `bookingOpensAt` 입력 | 공연 필드 추가, 회차 입력에서 제거, 공연 값 변경 시 모든 회차에 반영 |
| 예매 마감 기본값 전날 오후 5시 | 회차마다 필수 입력 | 선택 입력으로 바꾸고 공연장 시간대로 기본값 계산 |
| 첫 공개는 공연 예매 오픈이 미래 | 모든 회차의 예매 시작이 미래 | 공연 값 하나로 판정 |
| 공개 뒤 회차 추가 | 400 거절 | 시작·마감이 미래면 허용, 추가 회차는 수정 불가 |
| 비공개 전환·재공개 | 없음 | 공개 이력과 분리한 공개 여부 전환, 비공개 커밋 뒤 선점 거절 |
| 공개 이력 | `is_published`로 판정 | 별도 컬럼. 비공개로 돌려도 유지 |
| 주최 측 취소(100% 환불) | 없음 | 예약·티켓 "주최 측 취소" 상태, 전액 환불 배치 |
| soft delete | 물리 삭제, FK로 드롭·경매 참조 거절. 초안 공연은 회차가 없어야 삭제 | `deleted_at`. 모든 조회·겹침 검사에서 제외. 드롭·경매 FK는 더 이상 삭제를 막지 않는다 |
| 회차 삭제와 선점 경합 | 공개 이력 없는 회차만 삭제 | 회차 행 `PESSIMISTIC_WRITE` 아래 이력 확인. 선점은 공유 잠금 아래 `deleted_at`과 취소 여부를 확인 |
| 고객 환불 비율·취소 기한 | 기한·비율 확인 없이 전액 환불(`TicketPaymentService.requestCustomerRefund`) | 취소 기한 이후 거절. 부분 환불 지원 전에는 100% 구간 밖을 거절 |

## 3. 예정 스키마 변경

모두 새 마이그레이션으로 추가한다. dev에 병합된 마이그레이션은 고치지 않는다. dev 병합은 자동 배포되므로, 마이그레이션이 있는 PR은 병합 전에 배포 영향을 확인한다.

| 대상 | 변경 | 기존 데이터 |
|---|---|---|
| `concerts.booking_opens_at` | 추가, NULL 가능 | 회차들의 예매 오픈이 모두 같으면 그 값, 다르면 NULL |
| `concerts` 공개 이력 (예: `first_published_at` 또는 `ever_published`) | 추가 | 기존 공개 공연은 이력 있음. 실제 공개 시각을 모르면 시각을 만들지 않는다 |
| `concerts.deleted_at`, `performances.deleted_at` | 추가, NULL 가능 | 모두 NULL |
| `concerts.status`, `performances.status` | CANCELLED 값 사용 | 변경 없음 |
| 예약·티켓 | "주최 측 취소" 상태와 환불 처리 추적 값. 상태 CHECK 변경 가능성 | 변경 없음 |
| `performances.booking_opens_at` | 유지. 회차 등록 때 공연 값을 복사해 선점 코드를 바꾸지 않는다 | 변경 없음 |

## 4. 운영 데이터 확인 (마이그레이션 PR 전)

읽기 전용 SELECT다. 결과에 따라 기존 데이터는 자동 보정하지 않고 정책 10절대로 별도 확인한다.

```sql
-- 한 공연에서 회차 배치가 여러 개
SELECT concert_id, COUNT(DISTINCT layout_id) AS layouts
FROM performances GROUP BY concert_id HAVING COUNT(DISTINCT layout_id) > 1;

-- 한 공연에서 회차 예매 오픈이 여러 개
SELECT concert_id, COUNT(DISTINCT booking_opens_at) AS opens
FROM performances GROUP BY concert_id HAVING COUNT(DISTINCT booking_opens_at) > 1;

-- 같은 공연·등급인데 회차별 가격이 다름
SELECT p.concert_id, s.grade, COUNT(DISTINCT s.price) AS prices
FROM performance_seats s JOIN performances p ON p.id = s.performance_id
GROUP BY p.concert_id, s.grade HAVING COUNT(DISTINCT s.price) > 1;

-- 0원 판매 좌석
SELECT COUNT(*) FROM performance_seats WHERE price = 0;

-- 소요 시간이 없는데 회차가 있는 공연
SELECT c.id FROM concerts c
WHERE c.duration_minutes IS NULL AND EXISTS (SELECT 1 FROM performances p WHERE p.concert_id = c.id);

-- 비공개 공연 수 (주최자 API로 만든 초안)
SELECT COUNT(*) FROM concerts WHERE is_published = FALSE;
```

## 5. 향후 드롭·경매 연결 대비

정책 [8-1절](organizer-policy.md#8-1-티켓-보유자-전용-드롭경매-향후-연결)의 티켓 보유자 전용 드롭·경매 연결은 아직 드롭·경매 쪽에 없다. 쓰이지 않는 계약을 미리 만들지 않고, 티켓팅 구현이 아래를 깨지 않게 한다.

- 회차 ID를 안정적으로 유지한다. 티켓이 있는 회차는 물리 삭제하지 않는다(soft delete).
- 회차의 예매 마감·취소 기한·취소 여부를 concert application 조회로 얻을 수 있게 둔다. 드롭·경매가 시작 시점을 검사할 때 쓴다.
- 주최 측 취소는 발급 티켓을 취소 상태로 바꾼다. 그러면 보유 매수 조회가 자연히 0이 된다.
- 보유 매수 계약은 연결 작업 때 ticketing application에 추가한다. 기준은 (회원, 회차)별 발급 상태이며 취소되지 않은 티켓 수이고, 사용된 티켓도 센다.
- 회차 취소를 드롭·경매에 알리는 일은 후속 작업이므로 AFTER_COMMIT 이벤트 후보로 둔다. 핵심 상태(티켓·예약 취소)는 이벤트에 맡기지 않고 취소 트랜잭션에서 커밋한다.

## 6. 구현 단위 제안

1. **예매 시간 공연 단위화와 공개 이력:** `concerts.booking_opens_at`·공개 이력 마이그레이션, 회차 입력 변경, 예매 마감 기본값, 첫 공개 판정.
2. **공개 뒤 회차 추가와 비공개·재공개:** 추가 회차 검증·수정 차단, 비공개 전환·재공개 API, 비공개 커밋 뒤 선점 거절 동시성 테스트.
3. **soft delete:** `deleted_at` 마이그레이션, 조회·겹침·드롭 공개 확인에서 제외, 회차 삭제와 선점 경합 테스트.
4. **주최 측 취소와 전액 환불 배치:** 예약·티켓 상태, 배치, 불명확 결제 처리. 좌석 소유권·결제 불변식에 걸리므로 별도 PR로 한다.
5. **고객 환불 기한·비율:** 취소 기한과 100% 구간 확인. 부분 환불은 결제 담당자 정책에 따른다.
