package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * SKU head row (ADR-0005). Stock lives in {@code drop_inventory_buckets}; the SKU quantity is the bucket sum.
 * The quantity columns here are frozen at creation (or at the #330 backfill) and are kept only because they are
 * NOT NULL until a later contract migration drops them. Nothing reads them, so they have no getters.
 */
@Entity
@Table(name = "drop_inventories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropInventory {
    public static final int MAX_BUCKETS = 16;

    @Getter
    @Id @Column(name = "variant_id")
    private Long variantId;
    @Getter
    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;
    @Getter
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "bucket_count", nullable = false)
    private int bucketCount;
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;
    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    /** {@code bucketLimit} is the configured K; a SKU never gets more buckets than units (every bucket >= 1). */
    public static DropInventory initial(Long variantId, int quantity, int bucketLimit) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        if (bucketLimit < 1 || bucketLimit > MAX_BUCKETS) throw new IllegalArgumentException("bucket limit out of range");
        DropInventory inventory = new DropInventory();
        inventory.variantId = variantId;
        inventory.totalQuantity = quantity;
        inventory.availableQuantity = quantity;
        inventory.bucketCount = Math.min(bucketLimit, quantity);
        return inventory;
    }

    /** Bucket i gets floor(N / K), and the first N mod K buckets one more (same rule as the perf seed). */
    public int bucketQuantity(int bucketNo) {
        if (bucketNo < 0 || bucketNo >= bucketCount) throw new IllegalArgumentException("bucket out of range");
        return totalQuantity / bucketCount + (bucketNo < totalQuantity % bucketCount ? 1 : 0);
    }
}
