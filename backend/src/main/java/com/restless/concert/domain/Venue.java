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
@Table(name = "venues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Venue(String name, String address, String timezone, String status, Instant now) {
        this.name = Objects.requireNonNull(name);
        this.address = Objects.requireNonNull(address);
        this.timezone = Objects.requireNonNull(timezone);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
    }

    public static Venue create(String name, String address, String timezone, String status, Instant now) {
        return new Venue(name, address, timezone, status, now);
    }
}
