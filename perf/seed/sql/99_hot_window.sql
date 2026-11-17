-- 핫 이벤트 판매 기간과 Refresh Token 만료를 실행 시각 기준으로 다시 맞춘다.
-- 시드 마지막에 실행하고, 스냅샷을 복원한 뒤 실험 전에도 seed.sh --refresh로 다시 실행한다.
-- 재고, 점유, 주문은 건드리지 않는다.
SET @now = UTC_TIMESTAMP(6);

UPDATE drops
SET opens_at  = @now - INTERVAL 1 MINUTE,
    closes_at = @now + INTERVAL @hot_window_hours HOUR
WHERE id = 1;

UPDATE refresh_tokens
SET expires_at = @now + INTERVAL @token_ttl_hours HOUR
WHERE revoked_at IS NULL;

UPDATE auctions
SET starts_at = @now - INTERVAL 1 MINUTE,
    base_ends_at = @now + INTERVAL @hot_window_hours HOUR,
    ends_at = @now + INTERVAL @hot_window_hours HOUR
WHERE @close_minutes = 0 AND auction_id BETWEEN 1 AND 1 + @auction_spread AND status = 'OPEN'
  AND NOT EXISTS (SELECT 1 FROM auction_bids b WHERE b.auction_id = auctions.auction_id);

-- Refresh only untouched AU-7/8 lots. A changed revision or bid set excludes the entire lot.
SET @extra_first = 2 + @auction_spread + @auction_bg;
SET @ending_first = @extra_first + @heavy_users * @heavy_per_user;
SET @mass_first = @ending_first + @ending_count;
SET @extra_bid_first = (SELECT CAST(v AS UNSIGNED) FROM perf_seed_meta WHERE k = 'auction_extra_bid_first');
DROP TEMPORARY TABLE IF EXISTS _perf_refresh_auctions;
CREATE TEMPORARY TABLE _perf_refresh_auctions (auction_id BIGINT PRIMARY KEY);
INSERT INTO _perf_refresh_auctions
SELECT a.auction_id FROM auctions a WHERE a.status = 'OPEN' AND (
 (a.auction_id BETWEEN @ending_first AND @ending_first + @ending_count - 1 AND a.revision = 0
  AND NOT EXISTS (SELECT 1 FROM auction_bids b WHERE b.auction_id = a.auction_id))
 OR (a.auction_id BETWEEN @mass_first AND @mass_first + @mass_count - 1 AND a.revision = @mass_bids
  AND (SELECT COUNT(*) FROM auction_bids b WHERE b.auction_id = a.auction_id) = @mass_bids
  AND NOT EXISTS (SELECT 1 FROM auction_bids b WHERE b.auction_id = a.auction_id
   AND (b.auction_bid_id <> @extra_bid_first + 2 * @heavy_users * @heavy_per_user + (a.auction_id - @mass_first) * @mass_bids + b.bid_sequence - 1
        OR b.bid_sequence NOT BETWEEN 1 AND @mass_bids))));
UPDATE auctions a JOIN _perf_refresh_auctions r ON r.auction_id = a.auction_id
SET a.starts_at = @now - INTERVAL 1 MINUTE,
 a.base_ends_at = IF(@close_minutes = 0, @now + INTERVAL @hot_window_hours HOUR, @now + INTERVAL @close_minutes MINUTE),
 a.ends_at = IF(@close_minutes = 0, @now + INTERVAL @hot_window_hours HOUR, @now + INTERVAL @close_minutes MINUTE);
UPDATE auction_bids b JOIN _perf_refresh_auctions r ON r.auction_id = b.auction_id
SET b.accepted_at = @now - INTERVAL 30 SECOND + INTERVAL b.bid_sequence MICROSECOND;
UPDATE credit_holds h JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
JOIN _perf_refresh_auctions r ON r.auction_id = b.auction_id
SET h.held_at = b.accepted_at, h.released_at = IF(h.status = 'RELEASED', b.accepted_at + INTERVAL 1 MICROSECOND, NULL);
UPDATE credit_transactions t JOIN credit_holds h ON h.credit_hold_id = t.credit_hold_id
JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
JOIN _perf_refresh_auctions r ON r.auction_id = b.auction_id
SET t.created_at = b.accepted_at + INTERVAL IF(t.transaction_type = 'HOLD', 0, 1) MICROSECOND;
DROP TEMPORARY TABLE _perf_refresh_auctions;

-- 공통 시드의 티켓팅 예매 기간 갱신. 기존 예약·선점은 유지한다.
UPDATE performances p JOIN venue_layouts l ON l.id = p.layout_id
SET p.starts_at = UTC_TIMESTAMP(6) + INTERVAL 8 DAY,
    p.booking_opens_at = UTC_TIMESTAMP(6) - INTERVAL 1 DAY,
    p.booking_closes_at = UTC_TIMESTAMP(6) + INTERVAL 7 DAY
WHERE l.layout_code = 'PERF-TICKET';
