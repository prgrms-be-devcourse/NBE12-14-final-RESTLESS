package com.restless.ticketing.api.dto;

import com.restless.ticketing.application.dto.TicketCheckoutInfo;
import java.time.Instant;

public record TicketCheckoutResponse(Long reservationId, Long orderId, long totalAmount,
                                     Instant checkoutStartedAt, Instant paymentExpiresAt) {
    public static TicketCheckoutResponse from(TicketCheckoutInfo info) {
        return new TicketCheckoutResponse(info.reservationId(), info.orderId(), info.totalAmount(),
                info.checkoutStartedAt(), info.paymentExpiresAt());
    }
}
