-- Drop invariants. Every query returns the number of violating rows; 0 means the invariant holds.
-- Used by acceptance tests after each test and by traffic-test post-checks. Blocks start with "-- name: <id>".

-- name: INV-01 inventory conservation (total = available + reserved + sold)
SELECT COUNT(*) FROM drop_inventories
WHERE total_quantity <> available_quantity + reserved_quantity + sold_quantity;

-- name: INV-02 no negative quantity
SELECT COUNT(*) FROM drop_inventories
WHERE available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0;

-- name: INV-01/03 reserved quantity equals open stock holds of the same SKU
SELECT COUNT(*) FROM drop_inventories i
WHERE i.reserved_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = i.variant_id
      AND r.status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING'));

-- name: INV-01/03 sold quantity equals confirmed purchases of the same SKU
SELECT COUNT(*) FROM drop_inventories i
WHERE i.sold_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = i.variant_id AND r.status = 'CONFIRMED');

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
-- Confirm, release and refund restore all follow drop_reservations.variant_id (checked per SKU above),
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
