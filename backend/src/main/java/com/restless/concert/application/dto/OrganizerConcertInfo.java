package com.restless.concert.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record OrganizerConcertInfo(Long concertId, Long venueId, Long layoutId, Long organizerId, String title,
                                   String status, Instant createdAt, String venueName, String venueAddress,
                                   String description, LocalDate periodStartDate, LocalDate periodEndDate,
                                   Integer durationMinutes, boolean published, java.time.Instant publishedAt, boolean pricingConfigured) { }
