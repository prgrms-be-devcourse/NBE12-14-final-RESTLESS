-- Drop invariants. Every query returns the number of violating rows; 0 means the invariant holds.
-- Used by acceptance tests after each test and by traffic-test post-checks. Blocks start with "-- name: <id>".

-- Stock lives in drop_inventory_buckets (ADR-0005, #330). The SKU quantity is the bucket sum. The quantity
-- columns of drop_inventories are frozen and are not checked; only its total and bucket_count are live.

-- name: INV-01 inventory conservation per bucket (total = available + reserved + sold)
SELECT COUNT(*) FROM drop_inventory_buckets
WHERE total_quantity <> available_quantity + reserved_quantity + sold_quantity;

-- name: INV-01 SKU total equals the sum of its bucket totals
SELECT COUNT(*) FROM drop_inventories i
WHERE i.total_quantity <> (
    SELECT COALESCE(SUM(b.total_quantity), 0) FROM drop_inventory_buckets b WHERE b.variant_id = i.variant_id);

-- name: INV-02 no negative bucket quantity
SELECT COUNT(*) FROM drop_inventory_buckets
WHERE available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0;

-- name: INV-01/03 bucket reserved quantity equals open stock holds recorded on that bucket
SELECT COUNT(*) FROM drop_inventory_buckets b
WHERE b.reserved_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no
      AND r.status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING'));

-- name: INV-01/03 bucket sold quantity equals confirmed purchases recorded on that bucket
SELECT COUNT(*) FROM drop_inventory_buckets b
WHERE b.sold_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no AND r.status = 'CONFIRMED');

-- name: INV-03 every stock hold names an existing bucket of its SKU (bucket_no < bucket_count)
-- There is no physical FK from drop_reservations.bucket_no (it deadlocks, #327), so this query is the check.
SELECT COUNT(*) FROM drop_reservations r
LEFT JOIN drop_inventories i ON i.variant_id = r.variant_id
LEFT JOIN drop_inventory_buckets b ON b.variant_id = r.variant_id AND b.bucket_no = r.bucket_no
WHERE i.variant_id IS NULL OR r.bucket_no >= i.bucket_count OR b.variant_id IS NULL;

-- name: INV-01 a SKU has exactly bucket_count buckets numbered 0 .. bucket_count - 1
SELECT COUNT(*) FROM drop_inventories i
WHERE i.bucket_count <> (SELECT COUNT(*) FROM drop_inventory_buckets b WHERE b.variant_id = i.variant_id)
   OR EXISTS (SELECT 1 FROM drop_inventory_buckets b
              WHERE b.variant_id = i.variant_id AND b.bucket_no >= i.bucket_count);

-- name: INV-05 at most one open purchase per user and drop
SELECT COUNT(*) FROM (
    SELECT drop_id, user_id FROM drop_reservations
    WHERE status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING')
    GROUP BY drop_id, user_id HAVING COUNT(*) > 1) t;

-- name: INV-07 no duplicate drop order (every drop order is linked to exactly one stock hold)
-- A retried purchase request must reuse its order. An order for a drop SKU that no stock hold
-- points to is an extra order left by a retry. UNIQUE(order_id) already stops two holds sharing one.
SELECT COUNT(DISTINCT o.id) FROM orders o
JOIN order_items oi ON oi.order_id = o.id AND oi.item_type = 'DROP_VARIANT'
JOIN drop_variants v ON v.id = oi.item_ref_id
WHERE o.order_type = 'DROP'
  AND NOT EXISTS (SELECT 1 FROM drop_reservations r WHERE r.order_id = o.id);

-- name: INV-03 the order item is the SKU the stock hold reserved
-- Confirm, release and refund restore all follow drop_reservations.variant_id and bucket_no (checked per bucket above),
-- so the hold and its order must name the same SKU.
SELECT COUNT(*) FROM drop_reservations r
JOIN order_items oi ON oi.order_id = r.order_id
WHERE oi.item_type <> 'DROP_VARIANT' OR oi.item_ref_id <> r.variant_id;

-- name: INV-04 one drop order has exactly one item with quantity 1
SELECT COUNT(*) FROM drop_reservations r
WHERE (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = r.order_id) <> 1
   OR EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = r.order_id AND oi.quantity <> 1);

-- name: INV-08 order amount equals the SKU price snapshot
SELECT COUNT(*) FROM drop_reservations r
JOIN orders o ON o.id = r.order_id
JOIN order_items oi ON oi.order_id = r.order_id
WHERE o.total_amount <> oi.unit_price_snapshot;

-- name: INV-09 a paid order has a confirmed stock hold
SELECT COUNT(*) FROM drop_reservations r
JOIN orders o ON o.id = r.order_id
WHERE o.status = 'PAID' AND r.status <> 'CONFIRMED';
