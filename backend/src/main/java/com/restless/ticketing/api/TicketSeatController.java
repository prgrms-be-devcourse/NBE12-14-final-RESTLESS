package com.restless.ticketing.api;

import com.restless.ticketing.application.dto.TicketSeatInfo;
import com.restless.ticketing.application.dto.TicketSeatState;
import com.restless.ticketing.application.TicketSeatQueryService;
import com.restless.ticketing.application.TicketReservationQueryService;
import com.restless.ticketing.application.dto.TicketBookingAvailability;
import com.restless.auth.LoginMember;
import com.restless.concert.application.ConcertQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class TicketSeatController {

    private final TicketSeatQueryService ticketSeatQueryService;
    private final TicketReservationQueryService reservationQueries;
    private final ConcertQueryService concertQueries;

    @GetMapping("/{performanceId}/booking-availability")
    public TicketBookingAvailability availability(@LoginMember Long userId, @PathVariable Long performanceId) {
        return reservationQueries.getAvailability(userId, performanceId);
    }

    @GetMapping("/{performanceId}/seats")
    public List<TicketSeatInfo> getSeats(@PathVariable("performanceId") Long performanceId) {
        concertQueries.requirePublishedPerformance(performanceId);
        return ticketSeatQueryService.getSeats(performanceId);
    }

    @GetMapping("/{performanceId}/seats/status")
    public List<TicketSeatState> getSeatStates(@PathVariable("performanceId") Long performanceId) {
        return ticketSeatQueryService.getSeatStates(performanceId);
    }
}
