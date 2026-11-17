-- 로컬 전용. seed.sh가 한 연결에서 실행한다. Flyway 마이그레이션이 아니다.
-- 매번 새 샘플을 추가하며 기존 좌석·예약·주문·결제를 변경하지 않는다.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @sample_key = UUID();
SET @sample_now = UTC_TIMESTAMP(6);

-- seed.py가 동일 화면 미리보기 데이터(layout.json)를 이 연결의 변수로 전달한다.
CREATE TEMPORARY TABLE sample_ticketing_seats AS
SELECT * FROM JSON_TABLE(@sample_layout_json, '$.seats[*]' COLUMNS (
    section_code VARCHAR(10) PATH '$.sectionCode', row_label VARCHAR(10) PATH '$.rowLabel',
    seat_number VARCHAR(10) PATH '$.seatNumber', pos_x DECIMAL(10,2) PATH '$.x',
    pos_y DECIMAL(10,2) PATH '$.y', grade VARCHAR(20) PATH '$.grade',
    price BIGINT PATH '$.price', is_accessible BOOLEAN PATH '$.isAccessible'
)) AS sample;

START TRANSACTION;

-- 로그인할 수 없는 시드 전용 주최자. 구매자는 회원가입/로그인 API로 준비한다.
INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
VALUES (CONCAT('ticketing-', @sample_key, '@example.invalid'), '!login-disabled',
        '로컬 샘플 주최자', 'ORGANIZER', 'ACTIVE', @sample_now, @sample_now);
SET @sample_seller_id = LAST_INSERT_ID();

INSERT INTO venues (name, address, timezone, status, created_at)
VALUES ('올림픽홀 참고 가상 공연장', '공식 시설과 무관한 개발용 가상 배치', 'Asia/Seoul', 'ACTIVE', @sample_now);
SET @sample_venue_id = LAST_INSERT_ID();

INSERT INTO venue_sections (venue_id, code, name, floor, status)
VALUES (@sample_venue_id, 'A', 'A구역', '1F', 'ACTIVE'),
       (@sample_venue_id, 'B', 'B구역', '1F', 'ACTIVE'),
       (@sample_venue_id, 'C', 'C구역', '1F', 'ACTIVE');

INSERT INTO venue_seats (venue_id, section_id, row_label, seat_number, is_accessible, status)
SELECT @sample_venue_id, section.id, sample.row_label, sample.seat_number, sample.is_accessible, 'ACTIVE'
FROM sample_ticketing_seats sample
JOIN venue_sections section ON section.venue_id = @sample_venue_id AND section.code = sample.section_code
ORDER BY CAST(sample.row_label AS UNSIGNED), sample.section_code, CAST(sample.seat_number AS UNSIGNED);

INSERT INTO venue_layouts (venue_id, layout_code, version, name, status, published_at)
VALUES (@sample_venue_id, 'LOCAL-ARC-100', 1, '반원형 100석 가상 배치', 'PUBLISHED', @sample_now);
SET @sample_layout_id = LAST_INSERT_ID();

INSERT INTO layout_seats (layout_id, venue_seat_id, venue_id, section_label,
                          row_label, seat_number, pos_x, pos_y, is_sellable)
SELECT @sample_layout_id, seat.id, @sample_venue_id, section.name,
       seat.row_label, seat.seat_number, sample.pos_x, sample.pos_y, TRUE
FROM venue_seats seat
JOIN venue_sections section ON section.id = seat.section_id
JOIN sample_ticketing_seats sample ON sample.section_code = section.code
    AND sample.row_label = seat.row_label AND sample.seat_number = seat.seat_number
WHERE seat.venue_id = @sample_venue_id;

INSERT INTO concerts (venue_id, seller_id, title, status, created_at)
VALUES (@sample_venue_id, @sample_seller_id, CONCAT('[로컬 샘플] 티켓팅 ', LEFT(@sample_key, 8)),
        'SCHEDULED', @sample_now);
SET @sample_concert_id = LAST_INSERT_ID();

INSERT INTO performances (concert_id, venue_id, layout_id, starts_at, booking_opens_at,
                           booking_closes_at, max_tickets_per_user, status)
VALUES (@sample_concert_id, @sample_venue_id, @sample_layout_id,
        @sample_now + INTERVAL 8 DAY, @sample_now - INTERVAL 1 HOUR,
        @sample_now + INTERVAL 7 DAY, 4, 'SCHEDULED');
SET @sample_performance_id = LAST_INSERT_ID();

INSERT INTO performance_seats (performance_id, layout_id, venue_seat_id, grade, price, status, current_hold_id)
SELECT @sample_performance_id, @sample_layout_id, seat.id, sample.grade, sample.price, 'AVAILABLE', NULL
FROM venue_seats seat
JOIN venue_sections section ON section.id = seat.section_id
JOIN sample_ticketing_seats sample ON sample.section_code = section.code
    AND sample.row_label = seat.row_label AND sample.seat_number = seat.seat_number
WHERE seat.venue_id = @sample_venue_id ORDER BY seat.id;

COMMIT;
DROP TEMPORARY TABLE sample_ticketing_seats;

SELECT @sample_concert_id AS concert_id, @sample_performance_id AS performance_id,
       @sample_venue_id AS venue_id, @sample_layout_id AS layout_id;
SELECT grade, price, COUNT(*) AS seats FROM performance_seats
WHERE performance_id = @sample_performance_id GROUP BY grade, price;
-- 선점 API에는 venue_seat_id가 아닌 seat_id를 사용한다.
SELECT id AS seat_id, venue_seat_id, grade, price, status FROM performance_seats
WHERE performance_id = @sample_performance_id ORDER BY id LIMIT 4;
