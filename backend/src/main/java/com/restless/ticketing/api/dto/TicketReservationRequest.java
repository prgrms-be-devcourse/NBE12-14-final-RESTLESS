package com.restless.ticketing.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record TicketReservationRequest(
        @NotNull @Positive Long performanceId,
        @NotEmpty List<@NotNull @Positive Long> seatIds) {
}
