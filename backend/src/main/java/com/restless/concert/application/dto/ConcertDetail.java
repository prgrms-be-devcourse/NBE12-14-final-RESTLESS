package com.restless.concert.application.dto;

import java.time.Instant;

public record ConcertDetail(Long concertId, Long venueId, String title, String status, Instant createdAt) {
}
