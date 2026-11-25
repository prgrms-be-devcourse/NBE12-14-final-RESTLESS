package com.restless.concert.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ConcertDetail(Long concertId, Long venueId, String title, String status, Instant createdAt,
                            String venueName, String venueAddress, String description,
                            LocalDate periodStartDate, LocalDate periodEndDate, Integer durationMinutes) {
}
