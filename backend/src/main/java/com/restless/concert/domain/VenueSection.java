package com.restless.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "venue_sections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String floor;

    @Column(nullable = false, length = 20)
    private String status;

    private VenueSection(Long venueId, String code, String name, String floor, String status) {
        this.venueId = Objects.requireNonNull(venueId);
        this.code = Objects.requireNonNull(code);
        this.name = Objects.requireNonNull(name);
        this.floor = Objects.requireNonNull(floor);
        this.status = Objects.requireNonNull(status);
    }

    public static VenueSection create(Long venueId, String code, String name, String floor, String status) {
        return new VenueSection(venueId, code, name, floor, status);
    }
}
