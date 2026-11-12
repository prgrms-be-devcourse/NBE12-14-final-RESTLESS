package com.restless.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 배치와 실제 좌석의 조합을 식별하는 복합 키. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LayoutSeatId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "layout_id", nullable = false, updatable = false)
    private Long layoutId;

    @Column(name = "venue_seat_id", nullable = false, updatable = false)
    private Long venueSeatId;

    public LayoutSeatId(Long layoutId, Long venueSeatId) {
        this.layoutId = Objects.requireNonNull(layoutId);
        this.venueSeatId = Objects.requireNonNull(venueSeatId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LayoutSeatId that)) return false;
        return Objects.equals(layoutId, that.layoutId) && Objects.equals(venueSeatId, that.venueSeatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(layoutId, venueSeatId);
    }
}
