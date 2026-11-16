-- 공연장, 공연. drops.concert_id FK 때문에 드롭보다 먼저 만든다.
-- 드롭에 필요한 최소 행만 넣는다. 좌석, 회차 같은 티켓팅 데이터는 넣지 않는다.
-- 공연 1 = 핫 드롭용, 공연 2.. = 종료된 드롭 10개마다 1개.

INSERT INTO venues (id, name, address, timezone, status, created_at)
VALUES (1, 'PERF Arena', 'Seoul', 'Asia/Seoul', 'ACTIVE', @t0 - INTERVAL 60 DAY);

INSERT INTO concerts (id, venue_id, seller_id, title, status, created_at)
SELECT s.n + 1, 1, @users + 2, CONCAT('PERF Concert ', s.n + 1), 'SCHEDULED', @t0 - INTERVAL 60 DAY
FROM _perf_seq s
WHERE s.n < 1 + CEIL(@bg_drops / 10);
