package com.restless.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ticket_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "hold_request_hash", length = 32)
    private byte[] holdRequestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TicketReservationStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "checkout_started_at")
    private Instant checkoutStartedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "payment_expires_at")
    private Instant paymentExpiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private TicketReservation(Long userId, Long performanceId, String requestKey, byte[] holdRequestHash,
                              Instant holdExpiresAt, Instant now) {
        this.userId = Objects.requireNonNull(userId);
        this.performanceId = Objects.requireNonNull(performanceId);
        this.requestKey = Objects.requireNonNull(requestKey);
        Objects.requireNonNull(holdRequestHash);
        if (holdRequestHash.length != 32) {
            throw new IllegalArgumentException("Hold request hash must contain 32 bytes");
        }
        this.holdRequestHash = holdRequestHash.clone();
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt);
        this.createdAt = Objects.requireNonNull(now);
        this.status = TicketReservationStatus.HELD;
    }

    public static TicketReservation createHeld(Long userId, Long performanceId, String requestKey,
                                               byte[] holdRequestHash, Instant holdExpiresAt, Instant now) {
        return new TicketReservation(userId, performanceId, requestKey, holdRequestHash, holdExpiresAt, now);
    }

    public byte[] getHoldRequestHash() {
        return holdRequestHash == null ? null : holdRequestHash.clone();
    }

    public boolean isHoldExpired(Instant now) {
        return status == TicketReservationStatus.HELD && !now.isBefore(holdExpiresAt);
    }

    public void releaseExpiredHold(Instant now) {
        if (!isHoldExpired(now) || !canReleaseHold()) {
            throw new IllegalStateException("결제 전 만료된 선점 예약만 반환할 수 있습니다.");
        }
        status = TicketReservationStatus.RELEASED;
    }

    public boolean canReleaseHold() {
        return status == TicketReservationStatus.HELD
                && orderId == null && checkoutStartedAt == null && paymentExpiresAt == null;
    }

    public void startCheckout(Long orderId, Instant now, Instant paymentExpiresAt) {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(now);
        Objects.requireNonNull(paymentExpiresAt);
        if (!canReleaseHold() || !now.isBefore(holdExpiresAt) || !now.isBefore(paymentExpiresAt)) {
            throw new IllegalStateException("유효한 결제 전 선점 예약만 결제를 시작할 수 있습니다.");
        }
        this.orderId = orderId;
        this.checkoutStartedAt = now;
        this.paymentExpiresAt = paymentExpiresAt;
        this.status = TicketReservationStatus.PAYMENT_PENDING;
    }

    public void cancelHold() {
        if (!canReleaseHold()) {
            throw new IllegalStateException("결제 전 선점 예약만 취소할 수 있습니다.");
        }
        status = TicketReservationStatus.RELEASED;
    }
}
