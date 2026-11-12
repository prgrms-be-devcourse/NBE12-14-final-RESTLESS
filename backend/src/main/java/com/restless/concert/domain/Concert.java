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

@Entity
@Table(name = "concerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Concert(Long venueId, Long sellerId, String title, String status, Instant now) {
        this.venueId = Objects.requireNonNull(venueId);
        this.sellerId = Objects.requireNonNull(sellerId);
        this.title = Objects.requireNonNull(title);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
    }

    public static Concert create(Long venueId, Long sellerId, String title, String status, Instant now) {
        return new Concert(venueId, sellerId, title, status, now);
    }
}
