-- 공통 성능 시드에서 계정·공연 생성 후 실행한다. 기존 공연 1과 회원을 사용한다.
-- 회차 및 좌석은 자동 생성 ID를 사용하고 결과의 performance_id를 기록한다.
START TRANSACTION;
SET @venue = (SELECT venue_id FROM concerts WHERE id = 1);
INSERT INTO venue_sections (venue_id, code, name, floor, status) VALUES (@venue, 'PERF-TICKET', 'Perf', '1', 'ACTIVE');
SET @section = LAST_INSERT_ID();
INSERT INTO venue_layouts (venue_id, layout_code, version, name, status, published_at) VALUES (@venue, 'PERF-TICKET', 1, 'Perf', 'ACTIVE', UTC_TIMESTAMP(6));
SET @layout = LAST_INSERT_ID();
INSERT INTO venue_seats (venue_id, section_id, row_label, seat_number, is_accessible, status)
SELECT @venue, @section, CAST((id - 1) DIV 100 AS CHAR), CAST((id - 1) MOD 100 AS CHAR), 0, 'ACTIVE'
FROM users WHERE id BETWEEN 1 AND 1000;
INSERT INTO layout_seats (layout_id, venue_seat_id, venue_id, section_label, row_label, seat_number, pos_x, pos_y, is_sellable)
SELECT @layout, id, @venue, 'PERF', row_label, seat_number, CAST(seat_number AS UNSIGNED), CAST(row_label AS UNSIGNED), 1
FROM venue_seats WHERE section_id = @section;
INSERT INTO performances (concert_id, venue_id, layout_id, starts_at, booking_opens_at, booking_closes_at, max_tickets_per_user, status)
VALUES (1, @venue, @layout, UTC_TIMESTAMP(6) + INTERVAL 8 DAY, UTC_TIMESTAMP(6) - INTERVAL 1 DAY, UTC_TIMESTAMP(6) + INTERVAL 7 DAY, 3, 'SCHEDULED');
SET @performance = LAST_INSERT_ID();
INSERT INTO performance_seats (performance_id, layout_id, venue_seat_id, grade, price, status, current_hold_id)
SELECT @performance, @layout, venue_seat_id, 'VIP', 100000, 'AVAILABLE', NULL FROM layout_seats WHERE layout_id = @layout;
COMMIT;
SELECT @performance AS performance_id, COUNT(*) AS seat_count FROM performance_seats WHERE performance_id = @performance;
