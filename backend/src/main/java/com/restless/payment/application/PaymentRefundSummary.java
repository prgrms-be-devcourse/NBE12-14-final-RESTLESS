package com.restless.payment.application;

import com.restless.payment.domain.PaymentRefund;
import com.restless.payment.domain.PaymentRefundKind;
import com.restless.payment.domain.PaymentRefundStatus;

import java.time.Instant;

public record PaymentRefundSummary(
        Long refundId,
        PaymentRefundKind kind,
        PaymentRefundStatus status,
        Instant requestedAt,
        Instant completedAt) {
    static PaymentRefundSummary from(PaymentRefund refund) {
        return new PaymentRefundSummary(
                refund.getId(),
                refund.getKind(),
                refund.getStatus(),
                refund.getRequestedAt(),
                refund.getCompletedAt());
    }
}
