package com.restless.payment.infrastructure.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Global fake PG behavior for load tests. Rates apply to approvals without a per-order scenario.
 * unknownRate: timeout before the PG records anything. lostResponseRate: PG approves, response is lost.
 */
@ConfigurationProperties("pg.fake")
public record FakePgProperties(Duration approveDelay, double rejectRate, double unknownRate, double lostResponseRate) {
    public FakePgProperties {
        approveDelay = approveDelay == null ? Duration.ZERO : approveDelay;
        if (approveDelay.isNegative()) throw new IllegalArgumentException("Fake PG delay must be nonnegative");
        if (!Double.isFinite(rejectRate) || !Double.isFinite(unknownRate) || !Double.isFinite(lostResponseRate)
            || rejectRate < 0 || unknownRate < 0 || lostResponseRate < 0
            || rejectRate + unknownRate + lostResponseRate > 1) {
            throw new IllegalArgumentException("Fake PG rates must be finite, nonnegative and sum to at most one");
        }
    }
}
