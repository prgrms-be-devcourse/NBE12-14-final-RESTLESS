package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "drop_reservations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_drop_reservations_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_drop_reservations_request_key", columnNames = {"drop_id", "user_id", "request_key"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropReservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "drop_id", nullable = false)
    private Long dropId;
    @Column(name = "variant_id", nullable = false)
    private Long variantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "order_id", nullable = false)
    private Long orderId;
    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DropReservationStatus status;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "current_payment_id")
    private Long currentPaymentId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static DropReservation reserved(Long dropId, Long variantId, Long userId, Long orderId,
                                           String requestKey, Instant expiresAt, Instant now) {
        var reservation = new DropReservation();
        reservation.dropId = dropId;
        reservation.variantId = variantId;
        reservation.userId = userId;
        reservation.orderId = orderId;
        reservation.requestKey = requestKey;
        reservation.status = DropReservationStatus.RESERVED;
        reservation.expiresAt = expiresAt;
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    public boolean isCurrentPayment(Long paymentId) {
        return paymentId != null && paymentId.equals(currentPaymentId);
    }

    public boolean isApprovalPending() {
        return status == DropReservationStatus.CONFIRMING || status == DropReservationStatus.RECONCILING;
    }

    public boolean startApproval(Long paymentId, Instant now) {
        if (status != DropReservationStatus.RESERVED || !now.isBefore(expiresAt)) return false;
        currentPaymentId = Objects.requireNonNull(paymentId);
        changeTo(DropReservationStatus.CONFIRMING, now);
        return true;
    }

    public boolean markUnknown(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || status != DropReservationStatus.CONFIRMING) return false;
        changeTo(DropReservationStatus.RECONCILING, now);
        return true;
    }

    public boolean confirm(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || !isApprovalPending()) return false;
        changeTo(DropReservationStatus.CONFIRMED, now);
        return true;
    }

    public boolean compensate(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || !isApprovalPending()) return false;
        changeTo(DropReservationStatus.COMPENSATING, now);
        return true;
    }

    public boolean retryAfterFailure(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || !isApprovalPending() || !now.isBefore(expiresAt)) return false;
        changeTo(DropReservationStatus.RESERVED, now);
        return true;
    }

    public boolean releaseAfterFailure(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || !isApprovalPending() || now.isBefore(expiresAt)) return false;
        changeTo(DropReservationStatus.RELEASED, now);
        return true;
    }

    public boolean expire(Instant now) {
        if (status != DropReservationStatus.RESERVED || now.isBefore(expiresAt)) return false;
        changeTo(DropReservationStatus.EXPIRED, now);
        return true;
    }

    public boolean cancel(Instant now) {
        if (status != DropReservationStatus.RESERVED) return false;
        changeTo(DropReservationStatus.RELEASED, now);
        return true;
    }

    public boolean completeCompensation(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || status != DropReservationStatus.COMPENSATING) return false;
        changeTo(DropReservationStatus.RELEASED, now);
        return true;
    }

    public boolean restore(Long paymentId, Instant now) {
        if (!isCurrentPayment(paymentId) || status != DropReservationStatus.CONFIRMED) return false;
        changeTo(DropReservationStatus.RESTORED, now);
        return true;
    }

    private void changeTo(DropReservationStatus target, Instant now) {
        status = target;
        updatedAt = now;
    }
}
