-- Drop invariants. Every query returns the number of violating rows; 0 means the invariant holds.
-- Used by acceptance tests after each test and by traffic-test post-checks. Blocks start with "-- name: <id>".

-- name: INV-01 inventory conservation (total = available + reserved + sold)
SELECT COUNT(*) FROM drop_inventories
WHERE total_quantity <> available_quantity + reserved_quantity + sold_quantity;

-- name: INV-02 no negative quantity
SELECT COUNT(*) FROM drop_inventories
WHERE available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0;

-- name: reserved quantity equals open stock holds
SELECT COUNT(*) FROM drop_inventories i
WHERE i.reserved_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = i.variant_id
      AND r.status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING'));

-- name: sold quantity equals confirmed purchases
SELECT COUNT(*) FROM drop_inventories i
WHERE i.sold_quantity <> (
    SELECT COUNT(*) FROM drop_reservations r
    WHERE r.variant_id = i.variant_id AND r.status = 'CONFIRMED');

-- name: INV-05 at most one open purchase per user and drop
SELECT COUNT(*) FROM (
    SELECT drop_id, user_id FROM drop_reservations
    WHERE status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING')
    GROUP BY drop_id, user_id HAVING COUNT(*) > 1) t;

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
