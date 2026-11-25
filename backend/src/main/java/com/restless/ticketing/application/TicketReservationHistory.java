package com.restless.ticketing.application;

import com.restless.concert.application.ReservationHistory;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketReservationHistory implements ReservationHistory {
    private final SeatHoldRepository holds;
    private final TicketReservationRepository reservations;
    private final TicketSeatRepository seats;

    @Override
    public boolean hasSeatHold(Long performanceId) {
        return holds.existsByPerformanceId(performanceId) || reservations.existsByPerformanceId(performanceId);
    }

    @Override
    public void deleteUnreservedSeats(Long performanceId) {
        var found = seats.findAllByPerformanceId(performanceId);
        if (found.stream().anyMatch(s -> s.getStatus() != com.restless.ticketing.domain.TicketSeatStatus.AVAILABLE || s.getCurrentHoldId() != null))
            throw new com.restless.common.error.BusinessException(com.restless.common.error.ErrorCode.CONCERT_DELETE_CONFLICT);
        seats.deleteAll(found);
        seats.flush();
    }
}
