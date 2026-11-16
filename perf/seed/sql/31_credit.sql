-- Wallet IDs equal user IDs; hold IDs equal bid IDs. All background holds are closed.
INSERT INTO credit_wallets (credit_wallet_id, user_id, balance_amount, reserved_amount, created_at, updated_at)
SELECT u.id, u.id, @credit_grant - COALESCE(w.spent, 0), 0, @t0 - INTERVAL 3 DAY, @t0 - INTERVAL 1 DAY
FROM users u LEFT JOIN (
 SELECT b.bidder_user_id, SUM(b.bid_amount) AS spent FROM auction_bids b
 WHERE b.bid_sequence = @auction_bg_bids_per GROUP BY b.bidder_user_id
) w ON w.bidder_user_id = u.id WHERE u.role = 'USER';

INSERT INTO credit_holds (credit_hold_id, credit_wallet_id, auction_bid_id, held_amount, status, held_at, released_at, captured_at)
SELECT b.auction_bid_id, b.bidder_user_id, b.auction_bid_id, b.bid_amount,
 IF(b.bid_sequence = @auction_bg_bids_per, 'CAPTURED', 'RELEASED'), b.accepted_at,
 IF(b.bid_sequence = @auction_bg_bids_per, NULL, b.accepted_at + INTERVAL 1 SECOND),
 IF(b.bid_sequence = @auction_bg_bids_per, b.accepted_at + INTERVAL 1 SECOND, NULL)
FROM auction_bids b;

INSERT INTO credit_transactions (credit_wallet_id, credit_hold_id, transaction_type, balance_delta, reserved_delta, balance_after, reserved_after, operation_key, created_at)
SELECT u.id, NULL, 'GRANT', @credit_grant, 0, @credit_grant, 0,
 CONCAT('GRANT:', LPAD(HEX(u.id), 8, '0'), '-0000-4000-8000-000000000000'), @t0 - INTERVAL 3 DAY
FROM users u WHERE u.role = 'USER';

-- Cumulative captures give each record the wallet balance at that point in the synthetic history.
INSERT INTO credit_transactions (credit_wallet_id, credit_hold_id, transaction_type, balance_delta, reserved_delta, balance_after, reserved_after, operation_key, created_at)
SELECT x.bidder_user_id, x.auction_bid_id, 'HOLD', 0, x.bid_amount,
 @credit_grant - x.prior_spent, x.bid_amount, CONCAT('HOLD:', x.auction_bid_id), x.accepted_at
FROM (SELECT b.*, COALESCE(SUM(IF(b.bid_sequence = @auction_bg_bids_per, b.bid_amount, 0)) OVER
 (PARTITION BY b.bidder_user_id ORDER BY b.auction_bid_id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS prior_spent FROM auction_bids b) x;

INSERT INTO credit_transactions (credit_wallet_id, credit_hold_id, transaction_type, balance_delta, reserved_delta, balance_after, reserved_after, operation_key, created_at)
SELECT x.bidder_user_id, x.auction_bid_id, IF(x.bid_sequence = @auction_bg_bids_per, 'CAPTURE', 'RELEASE'),
 IF(x.bid_sequence = @auction_bg_bids_per, -x.bid_amount, 0), -x.bid_amount,
 @credit_grant - x.prior_spent - IF(x.bid_sequence = @auction_bg_bids_per, x.bid_amount, 0), 0,
 CONCAT(IF(x.bid_sequence = @auction_bg_bids_per, 'CAPTURE:', 'RELEASE:'), x.auction_bid_id),
 x.accepted_at + INTERVAL 1 SECOND
FROM (SELECT b.*, COALESCE(SUM(IF(b.bid_sequence = @auction_bg_bids_per, b.bid_amount, 0)) OVER
 (PARTITION BY b.bidder_user_id ORDER BY b.auction_bid_id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS prior_spent FROM auction_bids b) x;
