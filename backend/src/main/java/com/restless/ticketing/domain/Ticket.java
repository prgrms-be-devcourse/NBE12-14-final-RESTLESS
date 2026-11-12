package com.restless.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 좌석별 발급 티켓. 내부 PK와 사용자에게 제공하는 UUID 티켓번호를 구분한다. */
@Entity
@Table(name = "tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "performance_seat_id", nullable = false)
    private Long performanceSeatId;

    @Column(name = "ticket_no", nullable = false, length = 50, updatable = false)
    private String ticketNo;

    @Column(name = "price_amount", nullable = false)
    private long priceAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "used_at")
    private Instant usedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    private Ticket(Long reservationId, Long performanceId, Long performanceSeatId,
                   long priceAmount, String status, Instant now) {
        this.reservationId = Objects.requireNonNull(reservationId);
        this.performanceId = Objects.requireNonNull(performanceId);
        this.performanceSeatId = Objects.requireNonNull(performanceSeatId);
        this.priceAmount = priceAmount;
        this.status = Objects.requireNonNull(status);
        this.issuedAt = Objects.requireNonNull(now);
        this.ticketNo = UUID.randomUUID().toString();
    }

    public static Ticket issue(Long reservationId, Long performanceId, Long performanceSeatId,
                               long priceAmount, String status, Instant now) {
        return new Ticket(reservationId, performanceId, performanceSeatId, priceAmount, status, now);
    }
}
