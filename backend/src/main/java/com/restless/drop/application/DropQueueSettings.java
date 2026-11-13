package com.restless.drop.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DropQueueSettings(Duration heartbeatTimeout, Duration admissionTtl, int admitPerRun) {
    public DropQueueSettings(
            @Value("${restless.drop.queue.heartbeat-timeout:30s}") Duration heartbeatTimeout,
            @Value("${restless.drop.queue.admission-ttl:5m}") Duration admissionTtl,
            @Value("${restless.drop.queue.admit-per-run:100}") int admitPerRun) {
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()
                || admissionTtl.isNegative() || admissionTtl.isZero() || admitPerRun < 1)
            throw new IllegalArgumentException("Queue durations and batch size must be positive");
        this.heartbeatTimeout = heartbeatTimeout;
        this.admissionTtl = admissionTtl;
        this.admitPerRun = admitPerRun;
    }
}
