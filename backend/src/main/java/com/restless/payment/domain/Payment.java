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
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private PayableType payableType;

    private Long payableId;

    private Long userId;

    @Column(length = 20)
    private String provider;

    @Column(length = 200)
    private String paymentKey;

    @Column(length = 64)
    private String pgOrderId;

    @Column(length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private PaymentStatus status;

    private long amount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant approvedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant expiresAt;

    @Column(length = 100)
    private String failureCode;

    @Column(length = 500)
    private String failureMessage;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    public static Payment ready(PayableType payableType, Long payableId, Long userId, long amount,
                                String idempotencyKey, Instant expiresAt, Instant now) {
        Payment payment = new Payment();
        payment.payableType = Objects.requireNonNull(payableType);
        payment.payableId = Objects.requireNonNull(payableId);
        payment.userId = Objects.requireNonNull(userId);
        if (amount < 0) throw new IllegalArgumentException("amount must be nonnegative");
        payment.amount = amount;
        payment.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        payment.expiresAt = expiresAt;
        payment.provider = "TOSS";
        payment.pgOrderId = "PAY-" + UUID.randomUUID().toString().replace("-", "");
        payment.status = PaymentStatus.READY;
        payment.createdAt = Objects.requireNonNull(now);
        payment.updatedAt = now;
        return payment;
    }

    public boolean matchesAmount(long amount) { return this.amount == amount; }

    public void startProcessing(String paymentKey, Instant now) {
        Objects.requireNonNull(now);
        if (expiresAt != null && now.isAfter(expiresAt)) {
            throw new IllegalStateException("Payment approval deadline has passed");
        }
        Objects.requireNonNull(paymentKey);
        transition(PaymentStatus.PROCESSING, now);
        this.paymentKey = paymentKey;
    }

    public void approve(Instant approvedAt, Instant now) {
        if (status != PaymentStatus.PROCESSING && status != PaymentStatus.UNKNOWN) {
            throw new IllegalStateException("Approval requires PROCESSING or UNKNOWN");
        }
        Objects.requireNonNull(approvedAt);
        transition(PaymentStatus.APPROVED, now);
        this.approvedAt = approvedAt;
    }

    /** Call only after failure is confirmed, or to close an expired READY attempt. */
    public void fail(String code, String message, Instant now) {
        transition(PaymentStatus.FAILED, now);
        failureCode = code;
        failureMessage = message;
    }

    public void markUnknown(Instant now) { transition(PaymentStatus.UNKNOWN, now); }
    public void requestCancel(Instant now) { transition(PaymentStatus.CANCEL_PENDING, now); }
    public void completeCancel(Instant now) { transition(PaymentStatus.CANCELLED, now); }
    public void markCancelUnknown(Instant now) { transition(PaymentStatus.CANCEL_UNKNOWN, now); }

    public void revertCancel(Instant now) {
        if (status != PaymentStatus.CANCEL_PENDING && status != PaymentStatus.CANCEL_UNKNOWN) {
            throw new IllegalStateException("Cancellation rejection requires a pending cancellation");
        }
        transition(PaymentStatus.APPROVED, now);
    }

    private void transition(PaymentStatus target, Instant now) {
        Objects.requireNonNull(now);
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid payment transition: " + status + " -> " + target);
        }
        status = target;
        updatedAt = now;
    }
}
