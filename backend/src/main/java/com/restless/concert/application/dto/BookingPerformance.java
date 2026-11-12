package com.restless.concert.application.dto;

import java.time.Instant;
import java.util.Set;

public record BookingPerformance(Long layoutId, Instant bookingOpensAt, Instant bookingClosesAt,
                                 int maxTicketsPerUser, Set<Long> sellableSeatIds) {
    public BookingPerformance {
        sellableSeatIds = Set.copyOf(sellableSeatIds);
    }
}
