package com.restless.ticketing.application;

import com.restless.ticketing.application.dto.TicketReservationDetail;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TicketReservationQueryService {

    private static final Set<TicketReservationStatus> ACTIVE_STATUSES = Set.of(
            TicketReservationStatus.HELD, TicketReservationStatus.PAYMENT_PENDING, TicketReservationStatus.RECONCILING);

    private final TicketReservationRepository ticketReservationRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final JdbcTemplate jdbc;

    public TicketReservationDetail getDetail(Long userId, Long reservationId) {
        validateUser(userId);
        if (reservationId == null || reservationId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        TicketReservation reservation = ticketReservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toDetail(reservation,
                seatHoldRepository.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservationId), databaseNow());
    }

    public List<TicketReservationDetail> getActive(Long userId) {
        validateUser(userId);
        List<TicketReservation> reservations = ticketReservationRepository
                .findAllByUserIdAndStatusInOrderByCreatedAtDescIdDesc(userId, ACTIVE_STATUSES);
        if (reservations.isEmpty()) {
            return List.of();
        }
        List<Long> reservationIds = reservations.stream().map(TicketReservation::getId).toList();
        Map<Long, List<SeatHold>> holdsByReservation = seatHoldRepository
                .findAllByReservationIdInOrderByReservationIdAscPerformanceSeatIdAsc(reservationIds).stream()
                .collect(Collectors.groupingBy(SeatHold::getReservationId));
        Instant now = databaseNow();
        return reservations.stream()
                .map(reservation -> toDetail(reservation,
                        holdsByReservation.getOrDefault(reservation.getId(), List.of()), now))
                .toList();
    }

    private TicketReservationDetail toDetail(TicketReservation reservation, List<SeatHold> holds, Instant now) {
        List<TicketReservationDetail.ReservedSeat> seats = holds.stream()
                .map(hold -> new TicketReservationDetail.ReservedSeat(hold.getPerformanceSeatId(), hold.getPriceAmount()))
                .toList();
        return new TicketReservationDetail(reservation.getId(), reservation.getPerformanceId(), reservation.getStatus(),
                seats, reservation.getHoldExpiresAt(), reservation.isHoldExpired(now), reservation.getOrderId(),
                reservation.getPaymentExpiresAt(), reservation.getCreatedAt());
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
