# 예약·선점·티켓 관계 정합성

- 상태: DECIDED (2026-11-12, 담당자 합의).
- 구현: V20261112_1020__ticketing_enforce_integrity_and_hold_request_keys.sql 추가. 기존 파일은 수정하지 않는다.
- 적용 순서: 공통 결제 V20261112_1000 다음. 실제 DB 적용은 환경별 Flyway 결과로 확인한다.

## 복합 FK

| 자식 컬럼 | 부모 컬럼 | 보장 |
|---|---|---|
| seat_holds(reservation_id, performance_id) | ticket_reservations(id, performance_id) | 예약과 선점의 회차 일치 |
| seat_holds(performance_seat_id, performance_id) | performance_seats(id, performance_id) | 선점과 좌석의 회차 일치 |
| tickets(reservation_id, performance_id) | ticket_reservations(id, performance_id) | 예약과 티켓의 회차 일치 |
| tickets(performance_seat_id, performance_id) | performance_seats(id, performance_id) | 티켓과 좌석의 회차 일치 |
| performance_seats(id, current_hold_id) | seat_holds(performance_seat_id, id) | 현재 포인터가 해당 좌석의 선점을 가리킴 |

참조받는 컬럼 조합에는 명시적 UNIQUE를 추가한다. 기존 FK와 티켓 중복 발급 UNIQUE는 유지한다.

## 상태 CHECK

AVAILABLE이면 current_hold_id는 NULL, HELD/SOLD이면 NOT NULL이어야 한다. 그 외 상태는 거절한다.
NULL 포인터는 복합 FK의 참조 검사를 건너뛰지만 CHECK와 결합해 AVAILABLE에서만 허용한다.
좌석 상태와 포인터는 하나의 UPDATE로 변경한다.
AVAILABLE/NULL 좌석 생성 → 선점 기록 생성 → HELD/포인터 연결을 같은 트랜잭션에서 처리한다.
반환 시 AVAILABLE/NULL로 함께 바꾸며, 참조되는 이력은 삭제하지 않는다. MySQL FK는 트랜잭션 끝까지 검사를 유예하지 않는다.

## 보장 범위

이 FK는 티켓의 예약과 좌석이 같은 회차임을 보장하지만, 해당 예약이 실제 그 좌석을 선점했는지까지 직접 연결하지는 않는다.
발급 서비스는 잠금 아래 현재 선점의 reservation_id가 발급 예약과 같은지 검증한다. 티켓과 선점을 직접 연결하는 추가 FK는 이번 범위에 없다.
소유 선점의 HELD/CONFIRMED 상태와 만료·카운터·중복 결과 처리는 서비스 불변식으로 검증한다.

## 기존 데이터와 적용

아래 조회 결과가 없어야 한다. 데이터 자동 삭제·보정은 하지 않는다.
MySQL DDL의 파일 전체 롤백은 보장되지 않으므로 부분 실패 시 실제 제약과 Flyway 이력을 확인하고 복구한다.

```sql
SELECT c.* FROM seat_holds c
JOIN ticket_reservations r ON r.id = c.reservation_id
JOIN performance_seats s ON s.id = c.performance_seat_id
WHERE c.performance_id <> r.performance_id OR c.performance_id <> s.performance_id;

SELECT c.* FROM tickets c
JOIN ticket_reservations r ON r.id = c.reservation_id
JOIN performance_seats s ON s.id = c.performance_seat_id
WHERE c.performance_id <> r.performance_id OR c.performance_id <> s.performance_id;

SELECT s.* FROM performance_seats s
JOIN seat_holds h ON h.id = s.current_hold_id
WHERE h.performance_seat_id <> s.id;

SELECT * FROM performance_seats
WHERE NOT (
    (status = 'AVAILABLE' AND current_hold_id IS NULL)
    OR (status IN ('HELD', 'SOLD') AND current_hold_id IS NOT NULL)
);
```

## 검증 계획 (미실행)

- 같은 회차의 정상 예약·선점·티켓 연결을 허용한다.
- 다른 회차의 예약·선점·좌석·티켓 조합은 FK로 거절한다.
- 다른 좌석의 선점을 current_hold_id로 지정하면 거절한다.
- AVAILABLE/포인터 있음, HELD 또는 SOLD/포인터 없음, 알 수 없는 상태는 CHECK로 거절한다.
- 정상 생성 순서와 HELD→SOLD→AVAILABLE 전이 허용, 과거 선점 이력 보존.
- 같은 회차지만 다른 예약의 좌석을 발급하려는 경우 서비스 검증으로 거절한다.

## 이번 변경의 실행 검증

2026-11-12: backend에서 ./gradlew test --tests 'com.restless.payment.PaymentRepositoryTest' 실행 성공.
MySQL Testcontainers의 전체 Flyway 적용 및 JPA validate를 포함하는 기존 결제 연동 테스트다.
이번 관계 제약의 잘못된 INSERT/UPDATE 거절 시나리오별 전용 테스트는 아직 실행하지 않았다.
