package com.restless.payment.infrastructure.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("pg.fake")
public record FakePgProperties(Duration approveDelay, double rejectRate, double unknownRate) {
    public FakePgProperties {
        approveDelay = approveDelay == null ? Duration.ZERO : approveDelay;
        if (approveDelay.isNegative()) throw new IllegalArgumentException("Fake PG delay must be nonnegative");
        if (!Double.isFinite(rejectRate) || !Double.isFinite(unknownRate)
            || rejectRate < 0 || unknownRate < 0 || rejectRate + unknownRate > 1) {
            throw new IllegalArgumentException("Fake PG rates must be finite, nonnegative and sum to at most one");
        }
    }
}
