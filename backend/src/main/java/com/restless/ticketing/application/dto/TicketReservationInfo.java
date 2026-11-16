package com.restless.ticketing.application.dto;

import com.restless.ticketing.domain.TicketReservationStatus;
import java.time.Instant;
import java.util.List;

public record TicketReservationInfo(Long reservationId, TicketReservationStatus status,
                                     List<Long> seatIds, Instant holdExpiresAt) {
    public TicketReservationInfo {
        seatIds = List.copyOf(seatIds);
    }
}
