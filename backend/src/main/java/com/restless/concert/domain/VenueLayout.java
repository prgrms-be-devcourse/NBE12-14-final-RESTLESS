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
@Table(name = "venue_layouts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "layout_code", nullable = false, length = 30)
    private String layoutCode;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    private VenueLayout(Long venueId, String layoutCode, int version, String name,
                        String status, Instant publishedAt) {
        this.venueId = Objects.requireNonNull(venueId);
        this.layoutCode = Objects.requireNonNull(layoutCode);
        this.version = version;
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status);
        this.publishedAt = Objects.requireNonNull(publishedAt);
    }

    public static VenueLayout create(Long venueId, String layoutCode, int version, String name,
                                     String status, Instant publishedAt) {
        return new VenueLayout(venueId, layoutCode, version, name, status, publishedAt);
    }
}
