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
@Table(name = "venue_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "row_label", nullable = false, length = 10)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, length = 10)
    private String seatNumber;

    @Column(name = "is_accessible", nullable = false)
    private boolean accessible;

    @Column(nullable = false, length = 20)
    private String status;

    private VenueSeat(Long venueId, Long sectionId, String rowLabel, String seatNumber,
                      boolean accessible, String status) {
        this.venueId = Objects.requireNonNull(venueId);
        this.sectionId = Objects.requireNonNull(sectionId);
        this.rowLabel = Objects.requireNonNull(rowLabel);
        this.seatNumber = Objects.requireNonNull(seatNumber);
        this.accessible = accessible;
        this.status = Objects.requireNonNull(status);
    }

    public static VenueSeat create(Long venueId, Long sectionId, String rowLabel, String seatNumber,
                                   boolean accessible, String status) {
        return new VenueSeat(venueId, sectionId, rowLabel, seatNumber, accessible, status);
    }
}
