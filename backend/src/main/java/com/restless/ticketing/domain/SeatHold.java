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

/** 예약이 확보한 개별 좌석의 선점 이력. 현재 소유권은 판매 좌석의 포인터로 판단한다. */
@Entity
@Table(name = "seat_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "performance_seat_id", nullable = false)
    private Long performanceSeatId;

    @Column(name = "price_amount", nullable = false)
    private long priceAmount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SeatHoldStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "released_at")
    private Instant releasedAt;

    private SeatHold(Long reservationId, Long performanceId, Long performanceSeatId,
                       long priceAmount, Instant expiresAt, Instant now) {
        this.reservationId = Objects.requireNonNull(reservationId);
        this.performanceId = Objects.requireNonNull(performanceId);
        this.performanceSeatId = Objects.requireNonNull(performanceSeatId);
        this.priceAmount = priceAmount;
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.createdAt = Objects.requireNonNull(now);
        this.status = SeatHoldStatus.HELD;
    }

    public static SeatHold createHeld(Long reservationId, Long performanceId, Long performanceSeatId,
                                        long priceAmount, Instant expiresAt, Instant now) {
        return new SeatHold(reservationId, performanceId, performanceSeatId, priceAmount, expiresAt, now);
    }
}
