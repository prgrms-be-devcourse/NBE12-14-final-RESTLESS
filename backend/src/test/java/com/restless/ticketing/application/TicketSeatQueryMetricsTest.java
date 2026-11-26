package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.restless.common.observability.SeatQueryMetrics;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.ticketing.application.dto.TicketSeatSnapshot;
import com.restless.ticketing.domain.TicketSeatStatus;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import com.restless.ticketing.infrastructure.TicketSeatSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TicketSeatQueryMetricsTest {
    @Test
    void recordsRepositoryAndCombinationWhilePreservingMismatchFailure() {
        var repo = mock(TicketSeatSnapshotRepository.class);
        var concert = mock(ConcertQueryService.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new SeatQueryMetrics(registry, new MockEnvironment().withProperty("spring.profiles.active", "perf"));
        var service = new TicketSeatQueryService(mock(TicketSeatRepository.class), repo, concert, metrics);
        var seat = new TicketSeatSnapshot(1L, 2L, 3L, "VIP", 100L, TicketSeatStatus.AVAILABLE);
        when(concert.getPerformanceLayout(1L)).thenReturn(new PerformanceLayout(1L, List.of()));
        when(repo.findByPerformanceId(1L)).thenReturn(List.of(seat));
        assertThatThrownBy(() -> service.getSeats(1L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("회차의 판매 좌석과 배치가 일치하지 않습니다");
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "sale_seats", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("ticketing.seat.query.stage").tags("stage", "combine", "outcome", "error").timer().count()).isEqualTo(1);
    }
}
