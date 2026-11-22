package com.restless.drop.application;

import com.restless.drop.domain.DropInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** K for new SKUs (ADR-0005 D2). Read only at creation; each SKU stores its own bucket_count. */
@Component
public record DropInventorySettings(int bucketCount) {
    public DropInventorySettings(@Value("${restless.drop.inventory.bucket-count:4}") int bucketCount) {
        if (bucketCount < 1 || bucketCount > DropInventory.MAX_BUCKETS)
            throw new IllegalArgumentException("restless.drop.inventory.bucket-count must be 1..16");
        this.bucketCount = bucketCount;
    }
}
