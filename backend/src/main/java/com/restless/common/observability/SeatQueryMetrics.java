package com.restless.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Perf-only timings of seat query stages; repository timing includes mapping and may include pool wait. */
@Component
public class SeatQueryMetrics {
    private final MeterRegistry registry;
    private final boolean enabled;

    public SeatQueryMetrics(MeterRegistry registry, Environment environment) {
        this.registry = registry;
        this.enabled = environment.acceptsProfiles(Profiles.of("perf"));
    }

    public <T> T measure(String stage, Supplier<T> operation) {
        if (!enabled) {
            return operation.get();
        }
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return operation.get();
        } catch (RuntimeException | Error error) {
            outcome = "error";
            throw error;
        } finally {
            sample.stop(registry.timer("ticketing.seat.query.stage", "stage", stage, "outcome", outcome));
        }
    }
}
