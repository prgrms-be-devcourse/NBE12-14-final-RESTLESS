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
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TicketBookingAvailabilityQueryTest {
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
        when(reservations.findAllByUserIdAndStatusInOrderByCreatedAtDescIdDesc(any(), any())).thenReturn(List.of());
        var result = service.getAvailability(1L, 2L);
        assertThat(result.maximum()).isEqualTo(3);
        assertThat(result.remaining()).isEqualTo(3);
        assertThat(result.activeReservation()).isNull();
        verify(concerts, never()).getBookingPerformance(any());
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
