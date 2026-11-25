package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ReservationHistory;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.domain.TicketSeatStatus;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
        if (found.stream().anyMatch(s -> s.getStatus() != TicketSeatStatus.AVAILABLE || s.getCurrentHoldId() != null))
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        try {
            seats.deleteAll(found);
            seats.flush();
        } catch (DataIntegrityViolationException ex) {
            // 좌석을 참조하는 선점·티켓이 남아 있으면 삭제하지 않고 충돌로 응답한다.
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        }
    }
}
