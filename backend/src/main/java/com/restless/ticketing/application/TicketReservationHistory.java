package com.restless.ticketing.application;

import com.restless.concert.application.ReservationHistory;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketReservationHistory implements ReservationHistory {
    private final SeatHoldRepository holds;
    private final TicketReservationRepository reservations;

    @Override
    public boolean hasSeatHold(Long performanceId) {
        return holds.existsByPerformanceId(performanceId) || reservations.existsByPerformanceId(performanceId);
    }
}
