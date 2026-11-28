package com.restless.drop.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Admission cap per second for a whole drop, shared by every server (#551).
 * Each SKU gets {@code ceil(perDrop / skuCount)} per second on its gate row.
 * 0 keeps the previous behaviour: each batch admits up to available stock, bounded only by admit-per-run.
 */
@Component
public record DropQueueAdmissionRate(int perDrop) {
    public static final DropQueueAdmissionRate OFF = new DropQueueAdmissionRate(0);

    public DropQueueAdmissionRate(@Value("${restless.drop.queue.admit-rate-per-second:0}") int perDrop) {
        if (perDrop < 0) throw new IllegalArgumentException("Admission rate must not be negative");
        this.perDrop = perDrop;
    }

    public boolean enabled() {
        return perDrop > 0;
    }

    /** Per-second share of one SKU. Rounds up so a small drop never admits slower than the configured total. */
    public int perSku(long skuCount) {
        if (!enabled()) throw new IllegalStateException("Admission rate is off");
        long skus = Math.max(1L, skuCount);
        return (int) ((perDrop + skus - 1) / skus);
    }
}
