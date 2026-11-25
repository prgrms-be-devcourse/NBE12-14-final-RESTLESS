package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TicketBookingAvailabilityQueryTest {
    @Test
    void availabilityReturnsOnlyTheRequestedPerformanceReservation() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(SeatHoldRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var limits = mock(TicketPurchaseLimitRepository.class);
        var concerts = mock(ConcertQueryService.class);
        var service = new TicketReservationQueryService(reservations, holds, jdbc, limits, concerts);
        var reservation = mock(TicketReservation.class);
        when(concerts.getMaxTicketsPerUser(2L)).thenReturn(3);
        when(reservations.findFirstByUserIdAndPerformanceIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any(), any()))
                .thenReturn(Optional.of(reservation));
        when(reservation.getId()).thenReturn(10L);
        when(reservation.getPerformanceId()).thenReturn(2L);
        when(reservation.getStatus()).thenReturn(TicketReservationStatus.HELD);
        when(reservation.getHoldExpiresAt()).thenReturn(Instant.parse("2026-11-25T00:10:00Z"));
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class))
                .thenReturn(LocalDateTime.parse("2026-11-25T00:00:00"));

        var result = service.getAvailability(1L, 2L);

        assertThat(result.activeReservation().reservationId()).isEqualTo(10L);
        assertThat(result.activeReservation().performanceId()).isEqualTo(2L);
        verify(holds).findAllByReservationIdOrderByPerformanceSeatIdAsc(10L);
        verify(holds, never()).findAllByReservationIdInOrderByReservationIdAscPerformanceSeatIdAsc(any());
    }

    @Test
    void availabilityUsesLimitWithoutLoadingTheSeatLayout() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(SeatHoldRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var limits = mock(TicketPurchaseLimitRepository.class);
        var concerts = mock(ConcertQueryService.class);
        var service = new TicketReservationQueryService(reservations, holds, jdbc, limits, concerts);
        when(concerts.getMaxTicketsPerUser(2L)).thenReturn(3);
        when(limits.findFirstByUserIdAndPerformanceId(1L, 2L)).thenReturn(Optional.empty());
        when(reservations.findFirstByUserIdAndPerformanceIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        var result = service.getAvailability(1L, 2L);
        assertThat(result.maximum()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.activeReservation()).isNull();
        verify(concerts, never()).getBookingPerformance(any());
        verify(reservations).findFirstByUserIdAndPerformanceIdAndStatusInOrderByCreatedAtDescIdDesc(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(2L), any());
        verify(reservations, never()).findAllByUserIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void missingPerformanceKeepsNotFoundFailure() {
        var concerts = mock(ConcertQueryService.class);
        var service = new TicketReservationQueryService(mock(TicketReservationRepository.class),
                mock(SeatHoldRepository.class), mock(JdbcTemplate.class), mock(TicketPurchaseLimitRepository.class), concerts);
        var failure = new BusinessException(ErrorCode.NOT_FOUND, "공연 회차를 찾을 수 없습니다.");
        when(concerts.getMaxTicketsPerUser(2L)).thenThrow(failure);
        assertThatThrownBy(() -> service.getAvailability(1L, 2L)).isSameAs(failure);
    }
}
