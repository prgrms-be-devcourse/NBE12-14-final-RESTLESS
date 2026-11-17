-- Set @auction_mass_first and @auction_mass_last from seed/out/manifest.json before sourcing this file.
WITH closed AS (
 SELECT a.auction_id, a.ends_at, r.finalized_at, r.result_type,
        TIMESTAMPDIFF(MICROSECOND, a.ends_at, r.finalized_at) / 1000000.0 AS lag_seconds
 FROM auctions a JOIN auction_results r ON r.auction_id = a.auction_id
 WHERE a.auction_id BETWEEN @auction_mass_first AND @auction_mass_last AND a.status = 'CLOSED'
), ranked AS (
 SELECT *, ROW_NUMBER() OVER (ORDER BY lag_seconds, auction_id) AS rn, COUNT(*) OVER () AS total FROM closed
)
SELECT (SELECT COUNT(*) FROM auctions WHERE auction_id BETWEEN @auction_mass_first AND @auction_mass_last AND status = 'CLOSED') AS closed_count,
 (SELECT COUNT(*) FROM auctions WHERE auction_id BETWEEN @auction_mass_first AND @auction_mass_last AND status = 'OPEN') AS open_count,
 MIN(lag_seconds) AS lag_min_seconds,
 MAX(IF(rn = CEIL(total * 0.50), lag_seconds, NULL)) AS lag_p50_seconds,
 MAX(IF(rn = CEIL(total * 0.95), lag_seconds, NULL)) AS lag_p95_seconds,
 MAX(lag_seconds) AS lag_max_seconds,
 TIMESTAMPDIFF(MICROSECOND, MIN(finalized_at), MAX(finalized_at)) / 1000000.0 AS first_to_last_seconds,
 COUNT(*) / NULLIF(TIMESTAMPDIFF(MICROSECOND, MIN(finalized_at), MAX(finalized_at)) / 1000000.0, 0) AS closes_per_second,
 SUM(result_type = 'SOLD') AS sold_count, SUM(result_type = 'UNSOLD') AS unsold_count,
 (SELECT COUNT(*) FROM credit_holds h JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
  WHERE b.auction_id BETWEEN @auction_mass_first AND @auction_mass_last AND h.status = 'HELD') AS held_count
FROM ranked;
