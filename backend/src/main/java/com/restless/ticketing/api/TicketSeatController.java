package com.restless.ticketing.api;

import com.restless.ticketing.application.TicketSeatInfo;
import com.restless.ticketing.application.TicketSeatQueryService;
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

    @GetMapping("/{performanceId}/seats")
    public List<TicketSeatInfo> getSeats(@PathVariable("performanceId") Long performanceId) {
        return ticketSeatQueryService.getSeats(performanceId);
    }
}
