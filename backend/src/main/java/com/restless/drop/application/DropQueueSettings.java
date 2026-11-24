package com.restless.drop.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DropQueueSettings(Duration heartbeatTimeout, Duration heartbeatInterval, Duration admissionTtl, int admitPerRun) {
    public DropQueueSettings(
            @Value("${restless.drop.queue.heartbeat-timeout:40s}") Duration heartbeatTimeout,
            @Value("${restless.drop.queue.heartbeat-interval:#{null}}") Duration heartbeatInterval,
            @Value("${restless.drop.queue.admission-ttl:5m}") Duration admissionTtl,
            @Value("${restless.drop.queue.admit-per-run:100}") int admitPerRun) {
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()
                || admissionTtl.isNegative() || admissionTtl.isZero() || admitPerRun < 1)
            throw new IllegalArgumentException("Queue durations and batch size must be positive");
        this.heartbeatTimeout = heartbeatTimeout;
        // Keep shorter timeout-only configurations polling at the default 1:4 ratio.
        var defaultInterval = heartbeatTimeout.dividedBy(4);
        if (defaultInterval.compareTo(Duration.ofSeconds(10)) > 0) defaultInterval = Duration.ofSeconds(10);
        this.heartbeatInterval = heartbeatInterval == null ? defaultInterval : heartbeatInterval;
        if (this.heartbeatInterval.isZero() || this.heartbeatInterval.isNegative()
                || this.heartbeatInterval.compareTo(heartbeatTimeout) >= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive and shorter than timeout");
        this.admissionTtl = admissionTtl;
        this.admitPerRun = admitPerRun;
    }
}
