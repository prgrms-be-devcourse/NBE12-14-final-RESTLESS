package com.restless.ticketing.api;

import com.restless.ticketing.application.dto.TicketSeatInfo;
import com.restless.ticketing.application.dto.TicketSeatState;
import com.restless.ticketing.application.TicketSeatQueryService;
import com.restless.ticketing.application.TicketReservationQueryService;
import com.restless.ticketing.application.dto.TicketBookingAvailability;
import com.restless.auth.LoginMember;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final TicketSeatResponseCache seatResponses;

    @GetMapping("/{performanceId}/booking-availability")
    public TicketBookingAvailability availability(@LoginMember Long userId, @PathVariable Long performanceId) {
        return reservationQueries.getAvailability(userId, performanceId);
    }

    @GetMapping("/{performanceId}/seats")
    public ResponseEntity<?> getSeats(@PathVariable("performanceId") Long performanceId, HttpServletRequest request) {
        if (!seatResponses.enabled()) {
            // 회차 존재·공개 확인은 getSeats 트랜잭션 안에서 한다. 별도 트랜잭션은 DB 커넥션을 한 번 더 잡는다.
            List<TicketSeatInfo> seats = ticketSeatQueryService.getSeats(performanceId);
            return ResponseEntity.ok(seats);
        }
        // 회차별 응답 바이트를 짧게 공유한다 (ADR-0008). 압축 여부는 압축 필터가 정하고, 미리 만든 gzip을 쓴다.
        TicketSeatResponseCache.SeatResponse response = seatResponses.get(performanceId);
        TicketSeatCompressionFilter.offerPrecompressed(request, response.json(), response.gzip());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.json());
    }

    @GetMapping("/{performanceId}/seats/status")
    public ResponseEntity<?> getSeatStates(@PathVariable("performanceId") Long performanceId, HttpServletRequest request) {
        if (!seatResponses.enabled()) {
            List<TicketSeatState> states = ticketSeatQueryService.getSeatStates(performanceId);
            return ResponseEntity.ok(states);
        }
        // 상태 응답도 회차별로 짧게 공유하고 미리 만든 gzip을 쓴다 (ADR-0009).
        TicketSeatResponseCache.SeatResponse response = seatResponses.getStates(performanceId);
        TicketSeatCompressionFilter.offerPrecompressed(request, response.json(), response.gzip());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.json());
    }
}
