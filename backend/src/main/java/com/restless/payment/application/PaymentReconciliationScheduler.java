package com.restless.payment.application;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "restless.payment.reconcile.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentReconciliationScheduler {
    private final PaymentReconciler reconciler;

    @Scheduled(fixedDelayString = "${restless.payment.reconcile.fixed-delay-ms:5000}")
    public void run() {
        reconciler.runOnce();
    }
}
