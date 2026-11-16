-- 공연장, 공연. 드롭이 concert_id FK로 참조하므로 먼저 만든다.
-- 지금은 드롭에 필요한 최소 행만 있다. 좌석, 회차, 좌석 선점(핫·배경)은 티켓팅 담당 범위로 남겨 둔다.
-- 공연 1 = 핫 드롭용, 공연 2.. = 종료된 드롭 10개마다 1개.

INSERT INTO venues (id, name, address, timezone, status, created_at)
VALUES (1, 'PERF Arena', 'Seoul', 'Asia/Seoul', 'ACTIVE', @t0 - INTERVAL 60 DAY);

INSERT INTO concerts (id, venue_id, seller_id, title, status, created_at)
SELECT s.n + 1, 1, @users + 2, CONCAT('PERF Concert ', s.n + 1), 'SCHEDULED', @t0 - INTERVAL 60 DAY
FROM _perf_seq s
WHERE s.n < 1 + CEIL(@bg_drops / 10);
