-- New IDs follow the original background auctions and bids.
SET @extra_first = 2 + @auction_spread + @auction_bg;
SET @heavy_count = @heavy_users * @heavy_per_user;
SET @ending_first = @extra_first + @heavy_count;
SET @mass_first = @ending_first + @ending_count;
SET @mass_last = @mass_first + @mass_count - 1;
SET @heavy_first_user = @users - @heavy_users + 1;
SET @mass_first_user = @heavy_first_user - @mass_count * @mass_bids;
SET @extra_bid_first = (SELECT COALESCE(MAX(auction_bid_id), 0) + 1 FROM auction_bids);

INSERT INTO perf_seed_meta (k, v) VALUES
 ('auction_heavy_users', @heavy_users), ('auction_heavy_per_user', @heavy_per_user),
 ('auction_extra_first', @extra_first), ('auction_ending_first', @ending_first),
 ('auction_ending_count', @ending_count), ('auction_mass_first', @mass_first),
 ('auction_mass_count', @mass_count), ('auction_mass_bids', @mass_bids),
 ('auction_mass_first_user', @mass_first_user), ('auction_extra_bid_first', @extra_bid_first);

INSERT INTO auctions (auction_id, seller_user_id, performance_id, title, item_name, item_description,
 starting_price_amount, minimum_increment_amount, access_policy, required_performance_id, status,
 starts_at, base_ends_at, ends_at, extension_duration_seconds, closed_at, revision, created_at, updated_at)
SELECT @extra_first + n, @users + 2 + MOD(n, @organizers), NULL, CONCAT('Extra auction ', n + 1),
 'Performance item', 'Performance seed', @auction_start_price, @auction_min_increment, 'ALL', NULL,
 IF(n < @heavy_count, 'CLOSED', 'OPEN'),
 IF(n < @heavy_count, @t0 - INTERVAL 2 HOUR, @t0 - INTERVAL 1 MINUTE),
 IF(n < @heavy_count, @t0 - INTERVAL 1 HOUR + INTERVAL (n * 8 + 5) MICROSECOND, @t0 + INTERVAL @hot_window_hours HOUR),
 IF(n < @heavy_count, @t0 - INTERVAL 1 HOUR + INTERVAL (n * 8 + 5) MICROSECOND, @t0 + INTERVAL @hot_window_hours HOUR),
 60, IF(n < @heavy_count, @t0 - INTERVAL 1 HOUR + INTERVAL (n * 8 + 5) MICROSECOND, NULL),
 IF(n < @heavy_count, 3, IF(n < @heavy_count + @ending_count, 0, @mass_bids)),
 @t0 - INTERVAL 3 HOUR, @t0 - INTERVAL 30 MINUTE
FROM _perf_seq WHERE n < @heavy_count + @ending_count + @mass_count;

-- Each heavy member participates in its own auctions and its neighbour's, alternating winners.
INSERT INTO auction_bids (auction_bid_id, auction_id, bidder_user_id, bid_amount, bid_sequence, request_key, accepted_at)
SELECT @extra_bid_first + n, @extra_first + n DIV 2,
 @heavy_first_user + MOD(n DIV (2 * @heavy_per_user) + IF(MOD(n, 2) = MOD(n DIV 2, 2), 0, 1), @heavy_users),
 @auction_start_price + MOD(n, 2) * @auction_min_increment, MOD(n, 2) + 1,
 CONCAT('perf-extra-', @extra_bid_first + n), @t0 - INTERVAL 1 HOUR + INTERVAL (n * 4) MICROSECOND
FROM _perf_seq WHERE n < @heavy_count * 2;

INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at)
SELECT @extra_first + n, 'SOLD', @extra_bid_first + n * 2 + 1, @t0 - INTERVAL 1 HOUR + INTERVAL (n * 8 + 5) MICROSECOND
FROM _perf_seq WHERE n < @heavy_count;

-- Each mass-close bid has a distinct wallet; these IDs precede the heavy-member range.
INSERT INTO auction_bids (auction_bid_id, auction_id, bidder_user_id, bid_amount, bid_sequence, request_key, accepted_at)
SELECT @extra_bid_first + 2 * @heavy_count + n, @mass_first + n DIV @mass_bids,
 @mass_first_user + n, @auction_start_price + MOD(n, @mass_bids) * @auction_min_increment,
 MOD(n, @mass_bids) + 1, CONCAT('perf-extra-', @extra_bid_first + 2 * @heavy_count + n),
 @t0 - INTERVAL 30 SECOND + INTERVAL MOD(n, @mass_bids) MICROSECOND
FROM _perf_seq WHERE n < @mass_count * @mass_bids;
