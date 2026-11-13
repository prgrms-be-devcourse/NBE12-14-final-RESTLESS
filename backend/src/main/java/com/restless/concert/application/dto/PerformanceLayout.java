package com.restless.concert.application.dto;

import java.util.List;

public record PerformanceLayout(Long layoutId, List<SeatPosition> seats) {
    public PerformanceLayout {
        seats = List.copyOf(seats);
    }
}
