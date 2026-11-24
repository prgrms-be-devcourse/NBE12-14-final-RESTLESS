-- Covering index for the queue position COUNT (variant_id, status='WAITING', id <= ?, last_seen_at >= ?).
-- last_seen_at is in the index, so the COUNT reads only the index and stops at the caller's id.
ALTER TABLE drop_queue_entries
    ADD INDEX ix_drop_queue_sku_position (variant_id, status, id, last_seen_at);
