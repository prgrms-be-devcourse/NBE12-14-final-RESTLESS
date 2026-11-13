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

    /** 이 예약이 승인 시작을 받아들인 마지막 시도. 완료 후에도 결과 대조를 위해 보존한다. */
    @Column(name = "current_payment_id")
    private Long currentPaymentId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "approval_started_at")
    private Instant approvalStartedAt;

    /** 승인 결과 또는 필요한 보상 취소가 미확정이면 만료로 좌석을 반환하지 않는다. */
    @Column(name = "payment_result_pending", nullable = false)
    private boolean paymentResultPending;

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
                && orderId == null && checkoutStartedAt == null && paymentExpiresAt == null
                && currentPaymentId == null && approvalStartedAt == null && !paymentResultPending;
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

    /**
     * 호출자는 예약·좌석 잠금과 소유권 검증을 먼저 수행해야 한다.
     * 기한은 잠금 후 읽은 DB 시각(now), 시도 대조는 공통 결제의 원래 시작 시각으로 한다.
     * 업무상 시작할 수 없으면 필드를 변경하지 않고 false를 반환한다.
     */
    public boolean startApproval(Long paymentId, Instant attemptStartedAt, Instant now) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(attemptStartedAt, "attemptStartedAt");
        Objects.requireNonNull(now, "now");
        if (paymentId <= 0) {
            throw new IllegalArgumentException("결제 시도 ID는 양수여야 합니다.");
        }
        if (status != TicketReservationStatus.PAYMENT_PENDING || orderId == null
                || checkoutStartedAt == null || paymentExpiresAt == null
                || !now.isBefore(paymentExpiresAt)
                || attemptStartedAt.isBefore(checkoutStartedAt) || attemptStartedAt.isAfter(now)) {
            return false;
        }
        if (currentPaymentId != null && currentPaymentId.equals(paymentId)) {
            // 같은 ID의 재수신으로 최초 시각을 덮어쓰거나 최종 종료한 시도를 다시 열지 않는다.
            return paymentResultPending && matchesPaymentAttempt(paymentId, attemptStartedAt);
        }
        if (paymentResultPending) {
            return false;
        }
        currentPaymentId = paymentId;
        approvalStartedAt = attemptStartedAt;
        paymentResultPending = true;
        return true;
    }

    public boolean matchesPaymentAttempt(Long paymentId, Instant attemptStartedAt) {
        return currentPaymentId != null && approvalStartedAt != null
                && currentPaymentId.equals(paymentId) && approvalStartedAt.equals(attemptStartedAt);
    }

    public void cancelHold() {
        if (!canReleaseHold()) {
            throw new IllegalStateException("결제 전 선점 예약만 취소할 수 있습니다.");
        }
        status = TicketReservationStatus.RELEASED;
    }
}
