-- #330 DML only, after expand. Existing holds and sales all stay in bucket 0, where every open
-- reservation (bucket_no default 0) already points. Only the remaining available stock is split, into
-- at most 4 buckets. 4 is a constant because a migration cannot read the application setting.
UPDATE drop_inventories SET bucket_count = LEAST(4, GREATEST(available_quantity, 1));

INSERT INTO drop_inventory_buckets
    (variant_id, bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity)
WITH RECURSIVE seq (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < 15)
SELECT i.variant_id, s.n,
       FLOOR(i.available_quantity / i.bucket_count) + (s.n < MOD(i.available_quantity, i.bucket_count))
           + IF(s.n = 0, i.reserved_quantity + i.sold_quantity, 0),
       FLOOR(i.available_quantity / i.bucket_count) + (s.n < MOD(i.available_quantity, i.bucket_count)),
       IF(s.n = 0, i.reserved_quantity, 0),
       IF(s.n = 0, i.sold_quantity, 0)
FROM drop_inventories i
JOIN seq s ON s.n < i.bucket_count;
