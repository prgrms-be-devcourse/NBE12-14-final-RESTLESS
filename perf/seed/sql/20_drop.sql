-- 드롭. 핫 드롭 1건(판매 중, 점유 없음)과 종료된 배경 드롭.
-- 배경 점유의 order_id, current_payment_id는 90_order_payment.sql이 만들 주문·결제 ID(= 점유 ID)를 가리킨다.
-- seed.sh가 FOREIGN_KEY_CHECKS=0으로 실행하고, verify.sql이 FK 고아 행을 따로 검사한다.
--
-- ID 규칙
--   drops:         핫 1, 배경 d(0..) → d + 2
--   drop_variants: 핫 1..@hot_variants, 배경 → @hot_variants + 1 + d * @bg_vpd + j
--   drop_reservations: 배경 n(0..) → n + 1. 드롭 d = n DIV per_drop, 드롭 안 순번 k = n MOD per_drop

SET @bg_per_drop = CEIL(@bg_reservations / @bg_drops);

-- ---------------------------------------------------------------- 핫 드롭
-- 판매 기간은 99_hot_window.sql이 실행 시각 기준으로 다시 맞춘다 (스냅샷 복원 뒤에도 재실행).
INSERT INTO drops (id, concert_id, title, description, image_url, opens_at, closes_at, status, created_at)
VALUES (1, 1, 'PERF Hot Drop', 'Load test limited drop', 'https://perf.local/hot.png',
        @t0 - INTERVAL 1 MINUTE, @t0 + INTERVAL @hot_window_hours HOUR, 'OPEN', @t0 - INTERVAL 7 DAY);

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
SELECT s.n + 1, 1, CONCAT('HOT-', s.n + 1), CONCAT('Option ', s.n + 1), @hot_price, TRUE
FROM _perf_seq s
WHERE s.n < @hot_variants;

-- 총재고를 SKU에 나누고 나머지는 앞 SKU부터 1개씩 더한다. 합계가 @hot_stock과 같아야 한다 (verify.sql).
INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
SELECT v.id, q.qty, q.qty, 0, 0
FROM drop_variants v
JOIN (SELECT s.n + 1 AS variant_id,
             FLOOR(@hot_stock / @hot_variants) + (s.n < MOD(@hot_stock, @hot_variants)) AS qty
      FROM _perf_seq s
      WHERE s.n < @hot_variants) q ON q.variant_id = v.id
WHERE v.drop_id = 1;

-- 기본 0. 대기 상태 조회처럼 대기열이 차 있는 상황을 볼 때만 scale.yml에서 켠다.
INSERT INTO drop_queue_entries (drop_id, user_id, status, entered_at, last_seen_at)
SELECT 1, s.n + 1, 'WAITING', @t0, @t0
FROM _perf_seq s
WHERE s.n < @hot_queue_prefill;

-- ---------------------------------------------------------------- 종료된 배경 드롭
INSERT INTO drops (id, concert_id, title, description, image_url, opens_at, closes_at, status, created_at)
SELECT s.n + 2,
       2 + s.n DIV 10,
       CONCAT('PERF Archive Drop ', s.n + 1),
       'Closed drop for index depth',
       CONCAT('https://perf.local/archive-', s.n + 1, '.png'),
       @t0 - INTERVAL ((@bg_drops - s.n) * 48 + 24) HOUR,
       @t0 - INTERVAL ((@bg_drops - s.n) * 48) HOUR,
       'CLOSED',
       @t0 - INTERVAL ((@bg_drops - s.n) * 48 + 24 * 7) HOUR
FROM _perf_seq s
WHERE s.n < @bg_drops;

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
SELECT @hot_variants + 1 + s.n,
       2 + s.n DIV @bg_vpd,
       CONCAT('BG', s.n DIV @bg_vpd + 1, '-', MOD(s.n, @bg_vpd) + 1),
       CONCAT('Option ', MOD(s.n, @bg_vpd) + 1),
       @bg_price,
       TRUE
FROM _perf_seq s
WHERE s.n < @bg_drops * @bg_vpd;

-- 상태 버킷 (n * 37) MOD 100: 연속 100건마다 버킷이 한 번씩 나와 비율이 드롭마다 고르게 퍼진다.
-- 같은 드롭 안 사용자는 서로 다르다 (per_drop <= @users를 seed.sh가 확인).
INSERT INTO drop_reservations (id, drop_id, variant_id, user_id, order_id, request_key, status, expires_at,
                               created_at, updated_at, current_payment_id)
SELECT p.n + 1,
       p.d + 2,
       @hot_variants + 1 + p.d * @bg_vpd + MOD(p.k, @bg_vpd),
       MOD(p.d * 7919 + p.k, @users) + 1,
       p.n + 1,
       CONCAT('perf-bg-', p.n + 1),
       p.status,
       p.created_at + INTERVAL 5 MINUTE,
       p.created_at,
       CASE p.status
           WHEN 'CONFIRMED' THEN p.created_at + INTERVAL 40 SECOND
           WHEN 'RESTORED' THEN p.created_at + INTERVAL 1 DAY
           WHEN 'RELEASED' THEN p.created_at + INTERVAL 6 MINUTE
           ELSE p.created_at + INTERVAL 5 MINUTE
       END,
       p.n + 1
FROM (SELECT s.n,
             s.n DIV @bg_per_drop AS d,
             MOD(s.n, @bg_per_drop) AS k,
             @t0 - INTERVAL ((@bg_drops - s.n DIV @bg_per_drop) * 48 + 24) HOUR
                 + INTERVAL FLOOR(MOD(s.n, @bg_per_drop) * 80000 / @bg_per_drop) SECOND AS created_at,
             CASE
                 WHEN MOD(s.n * 37, 100) < @pct_confirmed THEN 'CONFIRMED'
                 WHEN MOD(s.n * 37, 100) < @pct_confirmed + @pct_restored THEN 'RESTORED'
                 WHEN MOD(s.n * 37, 100) < @pct_confirmed + @pct_restored + @pct_released THEN 'RELEASED'
                 ELSE 'EXPIRED'
             END AS status
      FROM _perf_seq s
      WHERE s.n < @bg_reservations) p;

-- 판매가 끝난 SKU: 점유 중 0, 판매 = CONFIRMED 수, 나머지는 반환되어 available. 점유가 없는 SKU는 재고 0.
INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
SELECT v.id,
       COALESCE(a.total, 0),
       COALESCE(a.total - a.sold, 0),
       0,
       COALESCE(a.sold, 0)
FROM drop_variants v
LEFT JOIN (SELECT variant_id, COUNT(*) AS total, SUM(status = 'CONFIRMED') AS sold
           FROM drop_reservations
           GROUP BY variant_id) a ON a.variant_id = v.id
WHERE v.drop_id <> 1;

-- 실제 구매 흐름처럼 점유마다 소비된 입장 1건과 비어 있는 구매 슬롯 1건을 남긴다.
INSERT INTO drop_queue_entries (drop_id, user_id, status, entered_at, last_seen_at, admitted_at, admitted_until,
                                consumed_at)
SELECT r.drop_id, r.user_id, 'CONSUMED',
       r.created_at - INTERVAL 2 MINUTE,
       r.created_at - INTERVAL 1 MINUTE,
       r.created_at - INTERVAL 1 MINUTE,
       r.created_at + INTERVAL 4 MINUTE,
       r.created_at
FROM drop_reservations r
ORDER BY r.id;

INSERT INTO drop_purchase_slots (drop_id, user_id, active_reservation_id, updated_at)
SELECT r.drop_id, r.user_id, NULL, r.updated_at
FROM drop_reservations r;
