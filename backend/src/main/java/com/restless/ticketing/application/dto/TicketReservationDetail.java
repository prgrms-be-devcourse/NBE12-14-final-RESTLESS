package com.restless.ticketing.application.dto;

import com.restless.ticketing.domain.TicketReservationStatus;
import java.time.Instant;
import java.util.List;

/** 가격은 현재 판매가가 아닌 최초 선점 당시의 스냅샷이다. */
public record TicketReservationDetail(Long reservationId, Long performanceId,
                                      TicketReservationStatus status, List<ReservedSeat> seats,
                                      Instant holdExpiresAt, boolean holdExpired,
                                      Long orderId, Instant paymentExpiresAt, Instant createdAt) {
    public TicketReservationDetail {
        seats = List.copyOf(seats);
    }

    public record ReservedSeat(Long seatId, long priceAmount) {
    }
}
