package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.restless.common.observability.SeatQueryMetrics;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TicketSeatQueryMetricsTest {
    @Test
    void recordsRepositoryAndCombinationWhilePreservingMismatchFailure() {
        var repo = mock(TicketSeatRepository.class);
        var concert = mock(ConcertQueryService.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new SeatQueryMetrics(registry, new MockEnvironment().withProperty("spring.profiles.active", "perf"));
        var service = new TicketSeatQueryService(repo, concert, metrics);
        var seat = mock(TicketSeat.class);
        when(seat.getLayoutId()).thenReturn(2L);
        when(seat.getVenueSeatId()).thenReturn(3L);
        when(concert.getPerformanceLayout(1L)).thenReturn(new PerformanceLayout(1L, List.of()));
        when(repo.findAllByPerformanceId(1L)).thenReturn(List.of(seat));
        assertThatThrownBy(() -> service.getSeats(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("회차의 판매 좌석과 배치가 일치하지 않습니다");
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "sale_seats", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "combine", "outcome", "error").timer().count()).isEqualTo(1);
    }
}
