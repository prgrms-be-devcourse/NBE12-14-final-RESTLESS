package com.restless.drop.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Queue admission is configured here, and implemented in D4. */
@Component
public record DropReservationSettings(Duration ttl, boolean queueRequired) {
    public DropReservationSettings(
            @Value("${restless.drop.reservation.ttl:PT5M}") Duration ttl,
            @Value("${restless.drop.queue.required:false}") boolean queueRequired) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Drop TTL must be positive");
        this.ttl = ttl;
        this.queueRequired = queueRequired;
    }
}
