package com.restless.ticketing.application.dto;

import java.time.Instant;

/** 최초 checkout에서 저장한 주문과 결제 기한. */
public record TicketCheckoutInfo(Long reservationId, Long orderId, long totalAmount,
                                 Instant checkoutStartedAt, Instant paymentExpiresAt) {
}
