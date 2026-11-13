package com.restless.drop.application.dto;

import com.restless.drop.domain.DropReservationStatus;
import java.time.Instant;

public final class DropReservationResponse {
    private DropReservationResponse() { }

    public record Created(Long reservationId, Long orderId, DropReservationStatus status,
                          long amount, Instant expiresAt) { }

    public record Detail(Long reservationId, Long orderId, Long variantId, DropReservationStatus status,
                         long amount, Instant expiresAt) { }

    public record Result(Created body, boolean replayed) { }
}
