package com.restless.payment.application;

import com.restless.payment.domain.PaymentStatus;

public record ConfirmPaymentResult(Long paymentId, PaymentStatus status) {}
