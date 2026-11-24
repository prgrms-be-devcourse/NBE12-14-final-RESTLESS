package com.restless.drop.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static com.restless.drop.domain.DropQueueStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class DropQueueEntryTest {
    private final Instant now = Instant.EPOCH;
    private final Duration timeout = Duration.ofSeconds(40);
    private final Duration interval = Duration.ofSeconds(10);
    private final DropQueueEntry entry = DropQueueEntry.waiting(1L, 2L, 3L, now);

    @Test
    void waitingBoundariesUseStoredHeartbeatAndSale() {
        assertThat(entry.heartbeatDue(now.plus(interval), interval)).isFalse();
        assertThat(entry.heartbeatDue(now.plus(interval).plusNanos(1000), interval)).isTrue();
        assertThat(entry.effectiveStatus(now.plus(timeout), timeout, true)).isEqualTo(WAITING);
        assertThat(entry.effectiveStatus(now.plus(timeout).plusNanos(1000), timeout, true)).isEqualTo(CLOSED);
        assertThat(entry.effectiveStatus(now, timeout, false)).isEqualTo(CLOSED);
        assertThat(entry.getStatus()).isEqualTo(WAITING);
    }

    @Test
    void admissionExpiresAtItsDeadlineEvenAfterSaleEnds() {
        var ttl = Duration.ofMinutes(5);
        entry.admit(now, ttl);
        assertThat(entry.effectiveStatus(now.plus(ttl).minusNanos(1000), timeout, false)).isEqualTo(ADMITTED);
        assertThat(entry.effectiveStatus(now.plus(ttl), timeout, false)).isEqualTo(EXPIRED);
        assertThat(entry.getStatus()).isEqualTo(ADMITTED);
    }

    @Test
    void expireClosesOnlyAdmissionPastDeadlineAndReportsItOnce() {
        var ttl = Duration.ofMinutes(5);
        assertThat(entry.expire(now.plus(ttl))).isFalse();
        assertThat(entry.getStatus()).isEqualTo(WAITING);
        entry.admit(now, ttl);
        assertThat(entry.expire(now.plus(ttl).minusNanos(1000))).isFalse();
        assertThat(entry.getStatus()).isEqualTo(ADMITTED);
        assertThat(entry.expire(now.plus(ttl))).isTrue();
        assertThat(entry.getStatus()).isEqualTo(EXPIRED);
        assertThat(entry.expire(now.plus(ttl))).isFalse();
        var consumed = DropQueueEntry.waiting(1L, 3L, 3L, now);
        consumed.admit(now, ttl);
        consumed.consume(now);
        assertThat(consumed.expire(now.plus(ttl).plusSeconds(1))).isFalse();
        assertThat(consumed.getStatus()).isEqualTo(CONSUMED);
    }
}
