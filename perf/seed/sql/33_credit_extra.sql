-- Preserve the existing ledger; append the new operations using its final balances.
DROP TABLE IF EXISTS _perf_extra_bids;
CREATE TABLE _perf_extra_bids AS
SELECT b.auction_bid_id, b.auction_id, b.bidder_user_id, b.bid_amount, b.bid_sequence, b.accepted_at,
 w.balance_amount AS initial_balance,
 COALESCE(SUM(IF(b.auction_id < @ending_first AND b.bid_sequence = 2, b.bid_amount, 0)) OVER
 (PARTITION BY b.bidder_user_id ORDER BY b.auction_bid_id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS prior_spent
FROM auction_bids b JOIN credit_wallets w ON w.user_id = b.bidder_user_id
WHERE b.auction_bid_id >= @extra_bid_first;
ALTER TABLE _perf_extra_bids ADD PRIMARY KEY (auction_bid_id);

INSERT INTO credit_holds (credit_hold_id, credit_wallet_id, auction_bid_id, held_amount, status, held_at, released_at, captured_at)
SELECT auction_bid_id, bidder_user_id, auction_bid_id, bid_amount,
 CASE WHEN auction_id < @ending_first AND bid_sequence = 2 THEN 'CAPTURED'
      WHEN auction_id >= @mass_first AND bid_sequence = @mass_bids THEN 'HELD' ELSE 'RELEASED' END,
 accepted_at,
 CASE WHEN (auction_id < @ending_first AND bid_sequence = 1) OR (auction_id >= @mass_first AND bid_sequence < @mass_bids) THEN accepted_at + INTERVAL 1 MICROSECOND END,
 CASE WHEN auction_id < @ending_first AND bid_sequence = 2 THEN accepted_at + INTERVAL 1 MICROSECOND END
FROM _perf_extra_bids;

INSERT INTO credit_transactions (credit_wallet_id, credit_hold_id, transaction_type, balance_delta, reserved_delta, balance_after, reserved_after, operation_key, created_at)
SELECT bidder_user_id, auction_bid_id, 'HOLD', 0, bid_amount, initial_balance - prior_spent, bid_amount,
 CONCAT('HOLD:', auction_bid_id), accepted_at FROM _perf_extra_bids;

INSERT INTO credit_transactions (credit_wallet_id, credit_hold_id, transaction_type, balance_delta, reserved_delta, balance_after, reserved_after, operation_key, created_at)
SELECT bidder_user_id, auction_bid_id, IF(auction_id < @ending_first AND bid_sequence = 2, 'CAPTURE', 'RELEASE'),
 IF(auction_id < @ending_first AND bid_sequence = 2, -bid_amount, 0), -bid_amount,
 initial_balance - prior_spent - IF(auction_id < @ending_first AND bid_sequence = 2, bid_amount, 0), 0,
 CONCAT(IF(auction_id < @ending_first AND bid_sequence = 2, 'CAPTURE:', 'RELEASE:'), auction_bid_id),
 accepted_at + INTERVAL 1 MICROSECOND
FROM _perf_extra_bids WHERE auction_id < @ending_first OR bid_sequence < @mass_bids;

UPDATE credit_wallets w JOIN (
 SELECT bidder_user_id,
 SUM(IF(auction_id < @ending_first AND bid_sequence = 2, bid_amount, 0)) AS spent,
 SUM(IF(auction_id >= @mass_first AND bid_sequence = @mass_bids, bid_amount, 0)) AS reserved
 FROM _perf_extra_bids GROUP BY bidder_user_id
) x ON x.bidder_user_id = w.user_id
SET w.balance_amount = w.balance_amount - x.spent, w.reserved_amount = w.reserved_amount + x.reserved;
