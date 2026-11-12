package com.restless.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "layout_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LayoutSeat {

    @EmbeddedId
    private LayoutSeatId id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "section_label", nullable = false, length = 50)
    private String sectionLabel;

    @Column(name = "row_label", nullable = false, length = 10)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, length = 10)
    private String seatNumber;

    @Column(name = "pos_x", nullable = false, precision = 10, scale = 2)
    private BigDecimal posX;

    @Column(name = "pos_y", nullable = false, precision = 10, scale = 2)
    private BigDecimal posY;

    @Column(name = "is_sellable", nullable = false)
    private boolean sellable;

    private LayoutSeat(LayoutSeatId id, Long venueId, String sectionLabel, String rowLabel,
                       String seatNumber, BigDecimal posX, BigDecimal posY, boolean sellable) {
        this.id = Objects.requireNonNull(id);
        this.venueId = Objects.requireNonNull(venueId);
        this.sectionLabel = Objects.requireNonNull(sectionLabel);
        this.rowLabel = Objects.requireNonNull(rowLabel);
        this.seatNumber = Objects.requireNonNull(seatNumber);
        this.posX = Objects.requireNonNull(posX);
        this.posY = Objects.requireNonNull(posY);
        this.sellable = sellable;
    }

    public static LayoutSeat create(LayoutSeatId id, Long venueId, String sectionLabel, String rowLabel,
                                    String seatNumber, BigDecimal posX, BigDecimal posY, boolean sellable) {
        return new LayoutSeat(id, venueId, sectionLabel, rowLabel, seatNumber, posX, posY, sellable);
    }
}
