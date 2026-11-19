-- DML checkpoint after expand. This does not rerun automatically after a later failure.
-- Legacy grants did not identify a SKU. Close them rather than invent a live entitlement.
UPDATE drop_queue_entries SET status = 'CLOSED' WHERE status IN ('WAITING', 'ADMITTED');
-- Consumed history can identify its actual SKU through the successful hold.
UPDATE drop_queue_entries q
LEFT JOIN drop_reservations r ON r.drop_id = q.drop_id AND r.user_id = q.user_id
    AND r.created_at >= q.entered_at AND r.created_at <= q.consumed_at
LEFT JOIN (SELECT drop_id, MIN(id) AS first_variant FROM drop_variants GROUP BY drop_id) v
    ON v.drop_id = q.drop_id
SET q.variant_id = COALESCE(r.variant_id, v.first_variant);
