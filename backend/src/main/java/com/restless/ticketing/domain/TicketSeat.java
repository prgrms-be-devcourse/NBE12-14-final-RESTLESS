package com.restless.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 회차별 판매 좌석. 배치나 실제 좌석의 상태와 구분한다. */
@Entity
@Table(name = "performance_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "layout_id", nullable = false)
    private Long layoutId;

    @Column(name = "venue_seat_id", nullable = false)
    private Long venueSeatId;

    @Column(nullable = false, length = 20)
    private String grade;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TicketSeatStatus status;

    @Column(name = "current_hold_id")
    private Long currentHoldId;

    private TicketSeat(Long performanceId, Long layoutId, Long venueSeatId, String grade, long price) {
        this.performanceId = Objects.requireNonNull(performanceId);
        this.layoutId = Objects.requireNonNull(layoutId);
        this.venueSeatId = Objects.requireNonNull(venueSeatId);
        this.grade = Objects.requireNonNull(grade);
        this.price = price;
        this.status = TicketSeatStatus.AVAILABLE;
    }

    public static TicketSeat createAvailable(Long performanceId, Long layoutId, Long venueSeatId,
                                             String grade, long price) {
        return new TicketSeat(performanceId, layoutId, venueSeatId, grade, price);
    }

    public void hold(Long holdId) {
        if (status != TicketSeatStatus.AVAILABLE || currentHoldId != null) {
            throw new IllegalStateException("선점 가능한 좌석이 아닙니다.");
        }
        currentHoldId = Objects.requireNonNull(holdId);
        status = TicketSeatStatus.HELD;
    }

    public void sell(Long holdId) {
        if (status != TicketSeatStatus.HELD || !Objects.equals(currentHoldId, holdId)) {
            throw new IllegalStateException("좌석의 현재 선점 소유자만 판매 확정할 수 있습니다.");
        }
        status = TicketSeatStatus.SOLD;
    }

    public void release(Long holdId) {
        if (status != TicketSeatStatus.HELD || !Objects.equals(currentHoldId, holdId)) {
            throw new IllegalStateException("좌석의 현재 선점 소유자가 일치하지 않습니다.");
        }
        status = TicketSeatStatus.AVAILABLE;
        currentHoldId = null;
    }
}
