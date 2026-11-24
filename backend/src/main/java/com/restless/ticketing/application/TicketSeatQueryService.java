package com.restless.ticketing.application;

import com.restless.common.observability.SeatQueryMetrics;
import com.restless.ticketing.application.dto.TicketSeatInfo;
import com.restless.ticketing.application.dto.TicketSeatState;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.concert.application.dto.SeatPosition;
import com.restless.ticketing.application.dto.TicketSeatSnapshot;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketSeatQueryService {

    private final TicketSeatRepository ticketSeatRepository;
    private final ConcertQueryService concertQueryService;
    private final SeatQueryMetrics seatQueryMetrics;

    @Transactional(readOnly = true)
    public List<TicketSeatState> getSeatStates(Long performanceId) {
        concertQueryService.requirePerformance(performanceId);
        return ticketSeatRepository.findStatesByPerformanceId(performanceId);
    }

    @Transactional(readOnly = true)
    public List<TicketSeatInfo> getSeats(Long performanceId) {
        PerformanceLayout layout = concertQueryService.getPerformanceLayout(performanceId);
        List<TicketSeatSnapshot> seats = seatQueryMetrics.measure("sale_seats",
                () -> ticketSeatRepository.findSnapshotsByPerformanceId(performanceId));
        return seatQueryMetrics.measure("combine", () -> combineSeats(layout, seats));
    }

    private List<TicketSeatInfo> combineSeats(PerformanceLayout layout, List<TicketSeatSnapshot> seats) {
        Map<Long, SeatPosition> positions = layout.seats().stream()
                .collect(Collectors.toMap(SeatPosition::venueSeatId, Function.identity()));

        return seats.stream()
                .map(seat -> {
                    SeatPosition position = positions.get(seat.venueSeatId());
                    if (!layout.layoutId().equals(seat.layoutId()) || position == null) {
                        throw new IllegalStateException("회차의 판매 좌석과 배치가 일치하지 않습니다: " + seat.id());
                    }
                    return new TicketSeatInfo(seat.id(), seat.venueSeatId(), position.sectionLabel(),
                            position.rowLabel(), position.seatNumber(), position.posX(), position.posY(),
                            seat.grade(), seat.price(), seat.status());
                })
                .toList();
    }
}
