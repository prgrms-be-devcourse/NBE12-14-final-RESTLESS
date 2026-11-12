package com.restless.ticketing.application;

import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.ConcertQueryService.PerformanceLayout;
import com.restless.concert.application.ConcertQueryService.SeatPosition;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.Comparator;
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

    @Transactional(readOnly = true)
    public List<TicketSeatInfo> getSeats(Long performanceId) {
        PerformanceLayout layout = concertQueryService.getPerformanceLayout(performanceId);
        Map<Long, SeatPosition> positions = layout.seats().stream()
                .collect(Collectors.toMap(SeatPosition::venueSeatId, Function.identity()));

        return ticketSeatRepository.findAllByPerformanceId(performanceId).stream()
                .sorted(Comparator.comparing(TicketSeat::getId))
                .map(seat -> {
                    SeatPosition position = positions.get(seat.getVenueSeatId());
                    if (!layout.layoutId().equals(seat.getLayoutId()) || position == null) {
                        throw new IllegalStateException("회차의 판매 좌석과 배치가 일치하지 않습니다: " + seat.getId());
                    }
                    return new TicketSeatInfo(seat.getId(), seat.getVenueSeatId(), position.sectionLabel(),
                            position.rowLabel(), position.seatNumber(), position.posX(), position.posY(),
                            seat.getGrade(), seat.getPrice(), seat.getStatus());
                })
                .toList();
    }
}
