-- 드롭. 핫 드롭 1건(판매 중, 점유 없음)과 종료된 배경 드롭.
-- 배경 점유의 order_id, current_payment_id는 90_order_payment.sql이 만들 주문·결제 ID(= 점유 ID)를 가리킨다.
-- seed.sh가 FOREIGN_KEY_CHECKS=0으로 실행하고, verify.sql이 FK 고아 행을 따로 검사한다.
--
-- ID 규칙
--   drops:         핫 1, 배경 d(0..) → d + 2, 워밍업 → @bg_drops + 2 (@warm_stock > 0일 때만)
--   drop_variants: 핫 1..@hot_variants, 배경 → @hot_variants + 1 + d * @bg_vpd + j, 워밍업은 배경 뒤 @hot_variants개
--   drop_reservations: 배경 n(0..) → n + 1. 드롭 d = n DIV per_drop, 드롭 안 순번 k = n MOD per_drop

SET @bg_per_drop = CEIL(@bg_reservations / @bg_drops);

-- 굿즈 종류. 배경 드롭 d는 굿즈 d MOD 10을 판다. 옵션이 3개보다 많으면 Ver.<n>을 쓴다. 95_cleanup.sql이 지운다.
DROP TABLE IF EXISTS _perf_products;
CREATE TABLE _perf_products (i INT PRIMARY KEY, name VARCHAR(50), price BIGINT,
                             opt1 VARCHAR(30), opt2 VARCHAR(30), opt3 VARCHAR(30));
INSERT INTO _perf_products VALUES
    (0, '공식 응원봉', 49000, '기본', '스페셜 패키지', '키링 세트'),
    (1, '투어 후드 티셔츠', 69000, 'M', 'L', 'XL'),
    (2, '포토카드 세트', 15000, 'A ver.', 'B ver.', 'C ver.'),
    (3, '슬로건 타월', 25000, '블랙', '화이트', '핑크'),
    (4, '아크릴 스탠드', 18000, 'Ver.1', 'Ver.2', 'Ver.3'),
    (5, '투어 키링', 12000, '실버', '골드', '로즈골드'),
    (6, '리유저블 백', 22000, '블랙', '아이보리', '네이비'),
    (7, '투어 볼캡', 35000, '블랙', '베이지', '네이비'),
    (8, '프로그램북', 30000, '일반판', '한정판', '디지털 코드 포함'),
    (9, '미니 포스터 세트', 20000, 'A ver.', 'B ver.', 'C ver.');

-- ---------------------------------------------------------------- 핫 드롭
-- 판매 기간은 99_hot_window.sql이 실행 시각 기준으로 다시 맞춘다 (스냅샷 복원 뒤에도 재실행).
INSERT INTO drops (id, concert_id, title, description, image_url, opens_at, closes_at, status, created_at)
SELECT 1, 1, CONCAT(a.name, ' <', a.tour, '> 공식 응원봉 한정판'),
       CONCAT(a.name, ' 2026 투어 기념 한정 수량 응원봉. 1인 1개 구매.'),
       'https://cdn.fanq.com/drops/hot.png',
       @t0 - INTERVAL 1 MINUTE, @t0 + INTERVAL @hot_window_hours HOUR, 'OPEN', @t0 - INTERVAL 7 DAY
FROM _perf_artists a
WHERE a.i = 0;

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
SELECT s.n + 1, 1, CONCAT('HOT-', s.n + 1),
       COALESCE(ELT(s.n + 1, '블랙', '화이트', '실버 한정'), CONCAT('Ver.', s.n + 1)), @hot_price, TRUE
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
       CONCAT(a.name, ' <', a.tour, '> ', p.name),
       CONCAT(a.name, ' ', 2026 - (1 + s.n DIV 10) DIV 10, ' 투어 공식 MD. 판매 종료.'),
       CONCAT('https://cdn.fanq.com/drops/archive-', s.n + 1, '.png'),
       @t0 - INTERVAL ((@bg_drops - s.n) * 48 + 24) HOUR,
       @t0 - INTERVAL ((@bg_drops - s.n) * 48) HOUR,
       'CLOSED',
       @t0 - INTERVAL ((@bg_drops - s.n) * 48 + 24 * 7) HOUR
FROM _perf_seq s
JOIN _perf_artists a ON a.i = MOD(1 + s.n DIV 10, 10)
JOIN _perf_products p ON p.i = MOD(s.n, 10)
WHERE s.n < @bg_drops;

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
SELECT @hot_variants + 1 + s.n,
       2 + s.n DIV @bg_vpd,
       CONCAT('BG', s.n DIV @bg_vpd + 1, '-', MOD(s.n, @bg_vpd) + 1),
       COALESCE(ELT(MOD(s.n, @bg_vpd) + 1, p.opt1, p.opt2, p.opt3), CONCAT('Ver.', MOD(s.n, @bg_vpd) + 1)),
       p.price,
       TRUE
FROM _perf_seq s
JOIN _perf_products p ON p.i = MOD(s.n DIV @bg_vpd, 10)
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

-- ---------------------------------------------------------------- 워밍업 드롭
-- 측정 전에 같은 경로를 데우는 판매 중 드롭. 배경 드롭의 재고·점유 INSERT가 이 드롭을 집지 않게 파일 끝에 둔다.
-- 핫 드롭과 SKU 수·가격·공연이 같다. @warm_stock이 0이면 만들지 않는다.
-- 핫 드롭 재고와 참여 계정을 쓰지 않으려고 따로 둔다. 판매 기간은 99_hot_window.sql이 핫 드롭과 같이 맞춘다.
INSERT INTO drops (id, concert_id, title, description, image_url, opens_at, closes_at, status, created_at)
SELECT @warm_drop_id, 1, CONCAT(a.name, ' <', a.tour, '> 워밍업 전용 응원봉'),
       '부하 테스트 워밍업 전용. 측정 대상이 아니다.',
       'https://cdn.fanq.com/drops/warmup.png',
       @t0 - INTERVAL 1 MINUTE, @t0 + INTERVAL @hot_window_hours HOUR, 'OPEN', @t0 - INTERVAL 7 DAY
FROM _perf_artists a
WHERE a.i = 0 AND @warm_stock > 0;

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
SELECT @warm_variant_first + s.n, @warm_drop_id, CONCAT('WARM-', s.n + 1),
       COALESCE(ELT(s.n + 1, '블랙', '화이트', '실버 한정'), CONCAT('Ver.', s.n + 1)), @hot_price, TRUE
FROM _perf_seq s
WHERE s.n < @hot_variants AND @warm_stock > 0;

INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
SELECT v.id, q.qty, q.qty, 0, 0
FROM drop_variants v
JOIN (SELECT @warm_variant_first + s.n AS variant_id,
             FLOOR(@warm_stock / @hot_variants) + (s.n < MOD(@warm_stock, @hot_variants)) AS qty
      FROM _perf_seq s
      WHERE s.n < @hot_variants) q ON q.variant_id = v.id
WHERE v.drop_id = @warm_drop_id AND @warm_stock > 0;
