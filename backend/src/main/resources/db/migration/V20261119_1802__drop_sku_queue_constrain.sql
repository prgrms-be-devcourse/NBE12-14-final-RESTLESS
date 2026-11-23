-- Precheck NULL variant_id before running. A failed atomic ALTER requires data correction and Flyway repair.
-- Closed legacy history without a hold uses the first SKU only as a historical FK.
-- A corrupt drop without variants fails NOT NULL validation instead of dropping history.
ALTER TABLE drop_queue_entries
    MODIFY variant_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_drop_queue_variant FOREIGN KEY (variant_id) REFERENCES drop_variants(id),
    DROP CHECK ck_drop_queue_status,
    ADD CONSTRAINT ck_drop_queue_status CHECK (status IN ('WAITING','ADMITTED','CONSUMED','EXPIRED','CLOSED','UNAVAILABLE')),
    ADD INDEX ix_drop_queue_sku_order (variant_id, status, id),
    ADD INDEX ix_drop_queue_sku_grants (variant_id, status, admitted_until);
