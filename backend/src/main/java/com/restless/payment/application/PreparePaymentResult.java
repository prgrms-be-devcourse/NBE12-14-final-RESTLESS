package com.restless.payment.application;

import com.restless.payment.domain.PaymentStatus;

import java.time.Instant;

public record PreparePaymentResult(
        Long paymentId,
        String pgOrderId,
        long amount,
        String orderName,
        Instant expiresAt,
        PaymentStatus status,
        boolean created) {}
