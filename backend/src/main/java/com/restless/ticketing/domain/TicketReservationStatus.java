package com.restless.ticketing.domain;

public enum TicketReservationStatus {
    HELD,
    PAYMENT_PENDING,
    RECONCILING,
    CONFIRMED,
    RELEASED,
    REFUND_PENDING,
    REFUND_RECONCILING,
    CANCELLED
}
