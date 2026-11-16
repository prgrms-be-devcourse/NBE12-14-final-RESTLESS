package com.restless.ticketing.application.dto;

import java.time.Instant;

public record IssuedTicketInfo(Long ticketId, String ticketNo, Long reservationId, Long orderId,
                               Long performanceId, Long concertId, String concertTitle, Instant startsAt,
                               Long venueId, Seat seat, long priceAmount, String status,
                               Instant issuedAt, Instant usedAt, Instant cancelledAt) {
    public record Seat(Long seatId, Long venueSeatId, String grade,
                       String sectionLabel, String rowLabel, String seatNumber) { }
}
