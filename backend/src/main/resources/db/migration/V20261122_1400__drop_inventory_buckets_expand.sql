-- #330 ADR-0005: split SKU inventory into buckets. DDL only; the data move is the next file.
-- MySQL DDL commits implicitly, so mixing it with the backfill could leave a half-applied state.
ALTER TABLE drop_inventories
    ADD COLUMN bucket_count TINYINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_drop_inventories_bucket_count CHECK (bucket_count BETWEEN 1 AND 16);

CREATE TABLE drop_inventory_buckets
(
    variant_id         BIGINT  NOT NULL,
    bucket_no          TINYINT NOT NULL,
    total_quantity     INT     NOT NULL,
    available_quantity INT     NOT NULL,
    reserved_quantity  INT     NOT NULL,
    sold_quantity      INT     NOT NULL,
    version            BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT pk_drop_inventory_buckets PRIMARY KEY (variant_id, bucket_no),
    CONSTRAINT fk_drop_inventory_buckets_inventory FOREIGN KEY (variant_id) REFERENCES drop_inventories (variant_id),
    CONSTRAINT ck_drop_inventory_buckets_no CHECK (bucket_no >= 0),
    CONSTRAINT ck_drop_inventory_buckets_non_negative CHECK (
        total_quantity >= 0 AND available_quantity >= 0 AND reserved_quantity >= 0 AND sold_quantity >= 0),
    CONSTRAINT ck_drop_inventory_buckets_conservation CHECK (
        total_quantity = available_quantity + reserved_quantity + sold_quantity)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- No physical FK to the bucket: the child INSERT's FK check takes an S lock on the parent bucket,
-- and the same transaction then upgrades it to X in the hold UPDATE, which deadlocks (#327 review).
-- INSTANT on MySQL 8.0.29+.
ALTER TABLE drop_reservations ADD COLUMN bucket_no TINYINT NOT NULL DEFAULT 0;
