package com.restless.payment.application;

import com.restless.payment.domain.PaymentRefundStatus;

public record RefundPaymentResult(Long refundId, PaymentRefundStatus status) {}
