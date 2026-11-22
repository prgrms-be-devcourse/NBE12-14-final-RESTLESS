-- Expand only. MySQL DDL is not transactionally reversible.
ALTER TABLE drop_queue_entries ADD COLUMN variant_id BIGINT NULL;

-- Separate from the inventory row: TX1 never acquires this gate.
CREATE TABLE drop_queue_gates (
    variant_id BIGINT NOT NULL PRIMARY KEY,
    CONSTRAINT fk_drop_queue_gate_variant FOREIGN KEY (variant_id) REFERENCES drop_variants(id)
) ENGINE = InnoDB;
INSERT INTO drop_queue_gates (variant_id) SELECT id FROM drop_variants;
