package com.restless.concert.application.dto;

import java.time.Instant;
import java.util.List;

/** 회차 잠금 아래 읽은 판매 가능 배치 좌석. */
public record SeatSetupLayout(Long performanceId, Long layoutId, Instant bookingOpensAt, List<Seat> seats) {
    public SeatSetupLayout { seats = List.copyOf(seats); }
    public record Seat(Long venueSeatId, String sectionLabel) { }
}
