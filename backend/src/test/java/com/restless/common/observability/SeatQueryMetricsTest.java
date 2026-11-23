package com.restless.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SeatQueryMetricsTest {
    @Test
    void perfRecordsSuccessfulAndFailedOperationsWithoutChangingTheirResults() {
        var registry = new SimpleMeterRegistry();
        var metrics = new SeatQueryMetrics(registry, new MockEnvironment().withProperty("spring.profiles.active", "perf"));
        assertThat(metrics.measure("sale_seats", () -> "result")).isEqualTo("result");
        var failure = new IllegalStateException("failure");
        assertThatThrownBy(() -> metrics.measure("combine", () -> { throw failure; })).isSameAs(failure);
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "sale_seats", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "combine", "outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void nonPerfDoesNotCreateTimers() {
        var registry = new SimpleMeterRegistry();
        var metrics = new SeatQueryMetrics(registry, new MockEnvironment());
        assertThat(metrics.measure("performance", () -> 42)).isEqualTo(42);
        assertThat(registry.getMeters()).isEmpty();
    }
}
