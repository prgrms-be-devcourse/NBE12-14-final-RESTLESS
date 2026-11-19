-- Legacy grants did not identify a SKU. Close them rather than invent a live entitlement.
UPDATE drop_queue_entries SET status = 'CLOSED' WHERE status IN ('WAITING', 'ADMITTED');
ALTER TABLE drop_queue_entries ADD COLUMN variant_id BIGINT NULL;
-- Consumed history can identify its actual SKU through the successful hold.
UPDATE drop_queue_entries q
LEFT JOIN drop_reservations r ON r.drop_id = q.drop_id AND r.user_id = q.user_id
    AND r.created_at >= q.entered_at AND r.created_at <= q.consumed_at
LEFT JOIN (SELECT drop_id, MIN(id) AS first_variant FROM drop_variants GROUP BY drop_id) v
    ON v.drop_id = q.drop_id
SET q.variant_id = COALESCE(r.variant_id, v.first_variant);
-- Closed legacy history without a hold uses the first SKU only as a historical FK.
-- A corrupt drop without variants fails NOT NULL validation instead of dropping history.
ALTER TABLE drop_queue_entries
    MODIFY variant_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_drop_queue_variant FOREIGN KEY (variant_id) REFERENCES drop_variants(id),
    DROP CHECK ck_drop_queue_status,
    ADD CONSTRAINT ck_drop_queue_status CHECK (status IN ('WAITING','ADMITTED','CONSUMED','EXPIRED','CLOSED','UNAVAILABLE')),
    ADD INDEX ix_drop_queue_sku_order (variant_id, status, id),
    ADD INDEX ix_drop_queue_sku_grants (variant_id, status, admitted_until);

-- Separate from the inventory row: TX1 never acquires this gate.
CREATE TABLE drop_queue_gates (
    variant_id BIGINT NOT NULL PRIMARY KEY,
    CONSTRAINT fk_drop_queue_gate_variant FOREIGN KEY (variant_id) REFERENCES drop_variants(id)
) ENGINE = InnoDB;
INSERT INTO drop_queue_gates (variant_id) SELECT id FROM drop_variants;
