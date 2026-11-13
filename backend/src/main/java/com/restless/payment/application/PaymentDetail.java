package com.restless.payment.application;

import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.Payment;
import com.restless.payment.domain.PaymentStatus;

import java.time.Instant;

public record PaymentDetail(
        Long paymentId,
        PayableType payableType,
        Long payableId,
        String pgOrderId,
        PaymentStatus status,
        long amount,
        Instant approvalStartedAt,
        Instant approvedAt,
        String failureCode,
        PaymentRefundSummary refund) {
    static PaymentDetail from(Payment p, PaymentRefundSummary refund) {
        return new PaymentDetail(
                p.getId(),
                p.getPayableType(),
                p.getPayableId(),
                p.getPgOrderId(),
                p.getStatus(),
                p.getAmount(),
                p.getApprovalStartedAt(),
                p.getApprovedAt(),
                p.getFailureCode(),
                refund);
    }
}
