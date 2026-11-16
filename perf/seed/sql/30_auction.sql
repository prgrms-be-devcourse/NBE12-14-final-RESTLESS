-- ID 1 is hot, 2..1+spread are spread, the remaining IDs are closed background auctions.
INSERT INTO perf_seed_meta (k, v) VALUES
 ('auction_spread', @auction_spread), ('auction_bg', @auction_bg),
 ('auction_bg_bids_per', @auction_bg_bids_per), ('auction_bg_pct_unsold', @auction_bg_pct_unsold);

INSERT INTO auctions (auction_id, seller_user_id, performance_id, title, item_name, item_description,
 starting_price_amount, minimum_increment_amount, access_policy, required_performance_id, status,
 starts_at, base_ends_at, ends_at, extension_duration_seconds, closed_at, revision, created_at, updated_at)
SELECT n + 1, @users + 2 + MOD(n, @organizers), NULL, CONCAT('Performance auction ', n + 1),
 CONCAT('Item ', n + 1), 'Performance seed', @auction_start_price, @auction_min_increment, 'ALL', NULL,
 IF(n <= @auction_spread, 'OPEN', 'CLOSED'),
 IF(n <= @auction_spread, @t0 - INTERVAL 1 MINUTE, @t0 - INTERVAL 2 DAY),
 IF(n <= @auction_spread, @t0 + INTERVAL @hot_window_hours HOUR, @t0 - INTERVAL 2 DAY + INTERVAL ((n - @auction_spread) * (@auction_bg_bids_per + 2)) SECOND),
 IF(n <= @auction_spread, @t0 + INTERVAL @hot_window_hours HOUR, @t0 - INTERVAL 2 DAY + INTERVAL ((n - @auction_spread) * (@auction_bg_bids_per + 2)) SECOND),
 60, IF(n <= @auction_spread, NULL, @t0 - INTERVAL 2 DAY + INTERVAL ((n - @auction_spread) * (@auction_bg_bids_per + 2)) SECOND), 0,
 @t0 - INTERVAL 3 DAY, @t0 - INTERVAL 1 DAY
FROM _perf_seq WHERE n < 1 + @auction_spread + @auction_bg;

INSERT INTO auction_bids (auction_bid_id, auction_id, bidder_user_id, bid_amount, bid_sequence, request_key, accepted_at)
SELECT s.n + 1, 2 + @auction_spread + s.n DIV @auction_bg_bids_per,
 MOD(s.n, @users) + 1, @auction_start_price + MOD(s.n, @auction_bg_bids_per) * @auction_min_increment,
 MOD(s.n, @auction_bg_bids_per) + 1, CONCAT('perf-auction-', s.n + 1),
 @t0 - INTERVAL 2 DAY + INTERVAL (s.n DIV @auction_bg_bids_per * (@auction_bg_bids_per + 2) + MOD(s.n, @auction_bg_bids_per) + 1) SECOND
FROM _perf_seq s
WHERE s.n < (@auction_bg - FLOOR(@auction_bg * @auction_bg_pct_unsold / 100)) * @auction_bg_bids_per;

INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at)
SELECT a.auction_id, IF(b.auction_bid_id IS NULL, 'UNSOLD', 'SOLD'), b.auction_bid_id, a.closed_at
FROM auctions a LEFT JOIN auction_bids b ON b.auction_id = a.auction_id AND b.bid_sequence = @auction_bg_bids_per
WHERE a.status = 'CLOSED';
