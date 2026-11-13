package com.restless.concert.application.dto;

import java.time.Instant;

public record PerformanceInfo(Long performanceId, Long concertId, Long venueId, Long layoutId,
                               Instant startsAt, Instant bookingOpensAt, Instant bookingClosesAt,
                               int maxTicketsPerUser, String status) {
}
