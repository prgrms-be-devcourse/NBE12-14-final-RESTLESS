package com.restless.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 공연의 날짜·시간별 회차. DB의 performances에 대응한다. */
@Entity
@Table(name = "performances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Performance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concert_id", nullable = false)
    private Long concertId;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "layout_id", nullable = false)
    private Long layoutId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "booking_opens_at", nullable = false)
    private Instant bookingOpensAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "booking_closes_at", nullable = false)
    private Instant bookingClosesAt;

    @Column(name = "max_tickets_per_user", nullable = false)
    private int maxTicketsPerUser;

    @Column(nullable = false, length = 20)
    private String status;

    private Performance(Long concertId, Long venueId, Long layoutId, Instant startsAt,
                           Instant bookingOpensAt, Instant bookingClosesAt, int maxTicketsPerUser,
                           String status) {
        this.concertId = Objects.requireNonNull(concertId);
        this.venueId = Objects.requireNonNull(venueId);
        this.layoutId = Objects.requireNonNull(layoutId);
        this.startsAt = Objects.requireNonNull(startsAt);
        this.bookingOpensAt = Objects.requireNonNull(bookingOpensAt);
        this.bookingClosesAt = Objects.requireNonNull(bookingClosesAt);
        this.maxTicketsPerUser = maxTicketsPerUser;
        this.status = Objects.requireNonNull(status);
    }

    public static Performance create(Long concertId, Long venueId, Long layoutId, Instant startsAt,
                                        Instant bookingOpensAt, Instant bookingClosesAt,
                                        int maxTicketsPerUser, String status) {
        return new Performance(concertId, venueId, layoutId, startsAt, bookingOpensAt,
                bookingClosesAt, maxTicketsPerUser, status);
    }
}
