package com.restless.ticketing.api;

import com.restless.auth.LoginMember;
import com.restless.ticketing.api.dto.TicketReservationRequest;
import com.restless.ticketing.application.dto.TicketReservationInfo;
import com.restless.ticketing.application.dto.TicketReservationDetail;
import com.restless.ticketing.application.TicketReservationQueryService;
import com.restless.ticketing.application.TicketReservationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final TicketReservationQueryService ticketReservationQueryService;

    @GetMapping("/active")
    public List<TicketReservationDetail> getActive(@LoginMember Long userId) {
        return ticketReservationQueryService.getActive(userId);
    }

    @GetMapping("/{reservationId}")
    public TicketReservationDetail getDetail(@LoginMember Long userId,
                                             @PathVariable("reservationId") Long reservationId) {
        return ticketReservationQueryService.getDetail(userId, reservationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketReservationInfo reserve(@LoginMember Long userId,
                                         @RequestHeader("Idempotency-Key") String requestKey,
                                         @Valid @RequestBody TicketReservationRequest request) {
        return ticketReservationService.reserve(userId, requestKey, request.performanceId(), request.seatIds());
    }

    @DeleteMapping("/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@LoginMember Long userId, @PathVariable("reservationId") Long reservationId) {
        ticketReservationService.cancel(userId, reservationId);
    }
}
