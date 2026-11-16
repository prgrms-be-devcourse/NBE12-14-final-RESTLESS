package com.restless.payment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("restless.payment.reconcile")
public record PaymentReconciliationProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("30s") Duration processingStaleAfter,
        @DefaultValue("10s") Duration unknownRetryAfter,
        @DefaultValue("30s") Duration cancelStaleAfter,
        @DefaultValue("15m") Duration notFoundFinalAfter) {
    public PaymentReconciliationProperties {
        if (batchSize < 1)
            throw new IllegalArgumentException("Reconciliation batch size must be positive");
        for (Duration duration :
                new Duration[] {
                    processingStaleAfter, unknownRetryAfter, cancelStaleAfter, notFoundFinalAfter
                }) {
            if (duration == null || duration.isNegative() || duration.isZero())
                throw new IllegalArgumentException("Reconciliation durations must be positive");
        }
    }
}
