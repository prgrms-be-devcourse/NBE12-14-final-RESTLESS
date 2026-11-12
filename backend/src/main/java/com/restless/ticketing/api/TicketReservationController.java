package com.restless.ticketing.api;

import com.restless.auth.LoginMember;
import com.restless.ticketing.api.dto.TicketReservationRequest;
import com.restless.ticketing.application.TicketReservationInfo;
import com.restless.ticketing.application.TicketReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ticket-reservations")
@RequiredArgsConstructor
public class TicketReservationController {

    private final TicketReservationService ticketReservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketReservationInfo reserve(@LoginMember Long userId,
                                         @RequestHeader("Idempotency-Key") String requestKey,
                                         @Valid @RequestBody TicketReservationRequest request) {
        return ticketReservationService.reserve(userId, requestKey, request.performanceId(), request.seatIds());
    }
}
