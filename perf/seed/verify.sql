-- 시드 직후 점검. 결과 행: kind, name, value
--   kind = check: value는 위반 행 수, 0이어야 한다. seed.sh는 0이 아닌 check가 있으면 실패한다.
--   kind = info:  행 수 같은 참고값.
-- 단독 실행: mysql ... restless < perf/seed/verify.sql
-- 드롭 불변식(INV-01~09)은 seed.sh가 backend/src/test/resources/harness/drop-invariants.sql을 그대로 실행해 함께 본다.
-- "seed baseline" 검사는 시드 직후 상태만 본다. 부하 테스트 뒤에는 실패하는 것이 정상이다.

SET SESSION group_concat_max_len = 65535;
DROP TEMPORARY TABLE IF EXISTS _perf_checks;
CREATE TEMPORARY TABLE _perf_checks
(
    kind  VARCHAR(10)  NOT NULL,
    name  VARCHAR(255) NOT NULL,
    value BIGINT       NOT NULL
);

-- ---------------------------------------------------------------- FK 고아 행 (스키마의 모든 FK)
-- 시드는 FOREIGN_KEY_CHECKS=0으로 넣으므로 DB 대신 여기서 확인한다. 복합 FK는 모든 컬럼이 NULL이 아닐 때만 본다 (MySQL FK 의미와 같다).
DROP PROCEDURE IF EXISTS _perf_check_fk_orphans;
DELIMITER //
CREATE PROCEDURE _perf_check_fk_orphans()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE v_sql TEXT;
    DECLARE cur CURSOR FOR
        SELECT CONCAT('INSERT INTO _perf_checks SELECT ''check'', ',
                      QUOTE(CONCAT('FK orphan ', k.TABLE_NAME, '.', k.CONSTRAINT_NAME)),
                      ', COUNT(*) FROM `', k.TABLE_NAME, '` c WHERE ',
                      GROUP_CONCAT(CONCAT('c.`', k.COLUMN_NAME, '` IS NOT NULL')
                                   ORDER BY k.ORDINAL_POSITION SEPARATOR ' AND '),
                      ' AND NOT EXISTS (SELECT 1 FROM `', k.REFERENCED_TABLE_NAME, '` p WHERE ',
                      GROUP_CONCAT(CONCAT('p.`', k.REFERENCED_COLUMN_NAME, '` = c.`', k.COLUMN_NAME, '`')
                                   ORDER BY k.ORDINAL_POSITION SEPARATOR ' AND '),
                      ')')
        FROM information_schema.KEY_COLUMN_USAGE k
        WHERE k.TABLE_SCHEMA = DATABASE()
          AND k.REFERENCED_TABLE_NAME IS NOT NULL
        GROUP BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.REFERENCED_TABLE_NAME;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    OPEN cur;
    fk_loop:
    LOOP
        FETCH cur INTO v_sql;
        IF done THEN
            LEAVE fk_loop;
        END IF;
        SET @perf_fk_sql = v_sql;
        PREPARE stmt FROM @perf_fk_sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END //
DELIMITER ;
CALL _perf_check_fk_orphans();
DROP PROCEDURE _perf_check_fk_orphans;

-- ---------------------------------------------------------------- 주문, 결제 금액과 상태
INSERT INTO _perf_checks
SELECT 'check', 'order total equals sum of items', COUNT(*)
FROM orders o
WHERE o.total_amount <> (SELECT COALESCE(SUM(oi.unit_price_snapshot * oi.quantity), -1)
                         FROM order_items oi WHERE oi.order_id = o.id);

INSERT INTO _perf_checks
SELECT 'check', 'drop payment amount equals order total', COUNT(*)
FROM payments p
JOIN drop_reservations r ON p.payable_type = 'DROP' AND r.id = p.payable_id
JOIN orders o ON o.id = r.order_id
WHERE p.amount <> o.total_amount;

INSERT INTO _perf_checks
SELECT 'check', 'drop reservation, order, payment have the same user', COUNT(*)
FROM drop_reservations r
JOIN orders o ON o.id = r.order_id
LEFT JOIN payments p ON p.id = r.current_payment_id
WHERE o.user_id <> r.user_id OR (p.id IS NOT NULL AND p.user_id <> r.user_id);

INSERT INTO _perf_checks
SELECT 'check', 'drop current payment belongs to its reservation', COUNT(*)
FROM drop_reservations r
JOIN payments p ON p.id = r.current_payment_id
WHERE p.payable_type <> 'DROP' OR p.payable_id <> r.id;

INSERT INTO _perf_checks
SELECT 'check', 'closed drop order status matches payment status', COUNT(*)
FROM drop_reservations r
JOIN orders o ON o.id = r.order_id
JOIN payments p ON p.id = r.current_payment_id
WHERE r.status IN ('CONFIRMED', 'RESTORED', 'RELEASED', 'EXPIRED')
  AND NOT ((r.status = 'CONFIRMED' AND o.status = 'PAID' AND p.status = 'APPROVED')
        OR (r.status = 'RESTORED' AND o.status = 'REFUNDED' AND p.status = 'CANCELLED')
        OR (r.status IN ('RELEASED', 'EXPIRED') AND o.status = 'CANCELLED' AND p.status = 'FAILED'));

INSERT INTO _perf_checks
SELECT 'check', 'cancelled payment has one completed refund of the full amount', COUNT(*)
FROM payments p
WHERE p.status = 'CANCELLED'
  AND (SELECT COUNT(*) FROM payment_refunds f
       WHERE f.payment_id = p.id AND f.status = 'COMPLETED' AND f.amount = p.amount) <> 1;

INSERT INTO _perf_checks
SELECT 'check', 'refund amount within payment amount', COUNT(*)
FROM payment_refunds f
JOIN payments p ON p.id = f.payment_id
WHERE f.amount > p.amount;

INSERT INTO _perf_checks
SELECT 'check', 'drop order has a shipping snapshot', COUNT(*)
FROM orders o
WHERE o.order_type = 'DROP'
  AND NOT EXISTS (SELECT 1 FROM order_shipping_snapshots s WHERE s.order_id = o.id);

-- ---------------------------------------------------------------- 시드 기준값 (seed baseline)
SET @meta_users = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'users');
SET @meta_staff = 1 + (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'organizers');
SET @meta_hot_drop = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'drop_hot_id');
SET @meta_hot_stock = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'drop_hot_stock');
SET @meta_bg_reservations = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'drop_bg_reservations');
-- 워밍업 드롭이 없는 시드와 이 키가 생기기 전의 시드는 0이다.
SET @meta_warm_drop = COALESCE((SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'drop_warm_id'), 0);
SET @meta_warm_stock = COALESCE((SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'drop_warm_stock'), 0);

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: user count (members + admin + organizers)', ABS(COUNT(*) - (@meta_users + @meta_staff))
FROM users;

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: one refresh token per account',
       ABS((SELECT COUNT(*) FROM refresh_tokens) - (@meta_users + @meta_staff));

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: hot drop total stock', ABS(COALESCE(SUM(i.total_quantity), 0) - @meta_hot_stock)
FROM drop_inventories i
JOIN drop_variants v ON v.id = i.variant_id
WHERE v.drop_id = @meta_hot_drop;

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: hot drop untouched (all stock available, no holds)',
       (SELECT COUNT(*) FROM drop_inventories i JOIN drop_variants v ON v.id = i.variant_id
        WHERE v.drop_id = @meta_hot_drop AND i.available_quantity <> i.total_quantity)
           + (SELECT COUNT(*) FROM drop_reservations WHERE drop_id = @meta_hot_drop)
           + (SELECT COUNT(*) FROM drop_purchase_slots WHERE drop_id = @meta_hot_drop);

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: background drop reservation count',
       ABS(COUNT(*) - @meta_bg_reservations)
FROM drop_reservations
WHERE drop_id NOT IN (@meta_hot_drop, @meta_warm_drop);

INSERT INTO _perf_checks
SELECT 'check', 'seed baseline: warmup drop total stock', ABS(COALESCE(SUM(i.total_quantity), 0) - @meta_warm_stock)
FROM drop_inventories i
JOIN drop_variants v ON v.id = i.variant_id
WHERE v.drop_id = @meta_warm_drop;

-- Auction seed baseline. Metadata is written by 30_auction.sql.
SET @auction_spread = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_spread');
SET @auction_bg = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_bg');
SET @auction_unsold_pct = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_bg_pct_unsold');
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: hot and spread auction count', ABS(COUNT(*) - (1 + @auction_spread)) FROM auctions WHERE auction_id BETWEEN 1 AND 1 + @auction_spread AND status = 'OPEN';
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: hot and spread no bids', COUNT(*) FROM auction_bids WHERE auction_id BETWEEN 1 AND 1 + @auction_spread;
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: background closed count', ABS(COUNT(*) - @auction_bg) FROM auctions WHERE auction_id BETWEEN 2 + @auction_spread AND 1 + @auction_spread + @auction_bg AND status = 'CLOSED';
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: background unsold count', ABS(COUNT(*) - FLOOR(@auction_bg * @auction_unsold_pct / 100)) FROM auction_results WHERE auction_id BETWEEN 2 + @auction_spread AND 1 + @auction_spread + @auction_bg AND result_type = 'UNSOLD';
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: wallet count', ABS((SELECT COUNT(*) FROM credit_wallets) - (SELECT COUNT(*) FROM users WHERE role = 'USER'));

SET @extra_first = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_extra_first');
SET @ending_first = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_ending_first');
SET @ending_count = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_ending_count');
SET @mass_first = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_mass_first');
SET @mass_count = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_mass_count');
SET @mass_bids = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_mass_bids');
SET @heavy_users = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_heavy_users');
SET @heavy_per = (SELECT CAST(v AS SIGNED) FROM perf_seed_meta WHERE k = 'auction_heavy_per_user');
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: heavy users history', COUNT(*) FROM users u WHERE u.id BETWEEN @meta_users - @heavy_users + 1 AND @meta_users AND (SELECT COUNT(DISTINCT b.auction_id) FROM auction_bids b WHERE b.bidder_user_id = u.id AND b.auction_id BETWEEN @extra_first AND @ending_first - 1) < @heavy_per;
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: ending auction count and state', ABS(CAST(COUNT(*) AS SIGNED) - CAST(@ending_count AS SIGNED)) FROM auctions a WHERE a.auction_id BETWEEN @ending_first AND @ending_first + @ending_count - 1 AND a.status = 'OPEN' AND NOT EXISTS (SELECT 1 FROM auction_bids b WHERE b.auction_id = a.auction_id);
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: mass close auctions and time', ABS(CAST(COUNT(*) AS SIGNED) - CAST(@mass_count AS SIGNED)) + IF(COUNT(DISTINCT ends_at) = 1, 0, 1) FROM auctions WHERE auction_id BETWEEN @mass_first AND @mass_first + @mass_count - 1 AND status = 'OPEN';
INSERT INTO _perf_checks SELECT 'check', 'seed baseline: mass close bids and held', COUNT(*) FROM auctions a WHERE a.auction_id BETWEEN @mass_first AND @mass_first + @mass_count - 1 AND ((SELECT COUNT(*) FROM auction_bids b WHERE b.auction_id = a.auction_id) <> @mass_bids OR (SELECT COUNT(*) FROM auction_bids b JOIN credit_holds h ON h.auction_bid_id = b.auction_bid_id WHERE b.auction_id = a.auction_id AND h.status = 'HELD') <> 1);

-- ---------------------------------------------------------------- 참고값
INSERT INTO _perf_checks
SELECT 'info', t.name, t.value
FROM (SELECT 'rows users' AS name, (SELECT COUNT(*) FROM users) AS value
      UNION ALL SELECT 'rows refresh_tokens', (SELECT COUNT(*) FROM refresh_tokens)
      UNION ALL SELECT 'rows drops', (SELECT COUNT(*) FROM drops)
      UNION ALL SELECT 'rows drop_variants', (SELECT COUNT(*) FROM drop_variants)
      UNION ALL SELECT 'rows drop_reservations', (SELECT COUNT(*) FROM drop_reservations)
      UNION ALL SELECT 'rows drop_queue_entries', (SELECT COUNT(*) FROM drop_queue_entries)
      UNION ALL SELECT 'rows drop_purchase_slots', (SELECT COUNT(*) FROM drop_purchase_slots)
      UNION ALL SELECT 'rows orders', (SELECT COUNT(*) FROM orders)
      UNION ALL SELECT 'rows order_items', (SELECT COUNT(*) FROM order_items)
      UNION ALL SELECT 'rows payments', (SELECT COUNT(*) FROM payments)
      UNION ALL SELECT 'rows payment_refunds', (SELECT COUNT(*) FROM payment_refunds)
      UNION ALL SELECT 'hot drop total stock', @meta_hot_stock
      UNION ALL SELECT 'completed refunds per mille of payments',
                       (SELECT ROUND(1000 * COUNT(*) / NULLIF((SELECT COUNT(*) FROM payments), 0))
                        FROM payment_refunds WHERE status = 'COMPLETED')) t;

SELECT kind, name, value FROM _perf_checks ORDER BY kind, value DESC, name;
DROP TEMPORARY TABLE _perf_checks;
