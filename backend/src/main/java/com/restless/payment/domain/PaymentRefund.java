package com.restless.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "payment_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private PaymentRefundStatus status;

    private long amount;

    @Column(length = 64)
    private String idempotencyKey;

    @Column(length = 64)
    private String cancelTransactionKey;

    @Column(length = 200)
    private String reason;

    @Column(length = 100)
    private String failureCode;

    @Column(length = 500)
    private String failureMessage;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant requestedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    public static PaymentRefund request(Long paymentId, long amount, String idempotencyKey,
                                        String reason, Instant now) {
        PaymentRefund refund = new PaymentRefund();
        refund.paymentId = Objects.requireNonNull(paymentId);
        if (amount <= 0) throw new IllegalArgumentException("refund amount must be positive");
        refund.amount = amount;
        refund.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        refund.reason = Objects.requireNonNull(reason);
        refund.status = PaymentRefundStatus.REQUESTED;
        refund.requestedAt = Objects.requireNonNull(now);
        refund.updatedAt = now;
        return refund;
    }

    public void complete(String transactionKey, Instant now) {
        Objects.requireNonNull(transactionKey);
        transition(PaymentRefundStatus.COMPLETED, now);
        cancelTransactionKey = transactionKey;
        completedAt = now;
    }

    public void markUnknown(Instant now) { transition(PaymentRefundStatus.UNKNOWN, now); }

    /** Call only after the PG confirms cancellation failure. */
    public void fail(String code, String message, Instant now) {
        transition(PaymentRefundStatus.FAILED, now);
        failureCode = code;
        failureMessage = message;
    }

    private void transition(PaymentRefundStatus target, Instant now) {
        Objects.requireNonNull(now);
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid refund transition: " + status + " -> " + target);
        }
        status = target;
        updatedAt = now;
    }
}
