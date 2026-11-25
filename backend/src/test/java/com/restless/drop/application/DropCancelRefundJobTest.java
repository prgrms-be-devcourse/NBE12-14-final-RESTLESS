package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.payment.application.PaymentRefundService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 새벽 환불의 기준 시각 (#435). 기준은 그날 00:30(KST)이고, 마감은 없다. */
class DropCancelRefundJobTest {

    private Instant cutoffPassedByRunOnce(String now) {
        var reservations = mock(DropReservationRepository.class);
        when(reservations.findCancelRefundCandidates(any(), any(), any())).thenReturn(List.of());
        new DropCancelRefundJob(reservations, mock(PaymentRefundService.class),
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC), new SimpleMeterRegistry()).runOnce();
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(reservations, atLeastOnce()).findCancelRefundCandidates(cutoff.capture(), any(), any());
        return cutoff.getValue();
    }

    @Test
    @DisplayName("00:30 KST(전날 15:30 UTC)에 돌면 기준은 그 시각이다")
    void cutoffAtScheduledRun() {
        assertThat(cutoffPassedByRunOnce("2026-11-24T15:30:00Z")).isEqualTo(Instant.parse("2026-11-24T15:30:00Z"));
    }

    @Test
    @DisplayName("서버가 늦게 시작해 07:59 KST에 돌아도 기준은 그날 00:30이다. 07:59 삭제는 다음 날로 넘어간다")
    void lateRunKeepsSameCutoff() {
        assertThat(cutoffPassedByRunOnce("2026-11-24T22:59:00Z")).isEqualTo(Instant.parse("2026-11-24T15:30:00Z"));
    }

    @Test
    @DisplayName("00:30 KST 전에 손으로 돌리면 기준은 전날 00:30이다")
    void manualRunBeforeScheduleUsesPreviousDay() {
        assertThat(cutoffPassedByRunOnce("2026-11-24T15:29:59Z")).isEqualTo(Instant.parse("2026-11-23T15:30:00Z"));
    }

    @Test
    @DisplayName("마감이 없다. 08:00 KST가 지나도 남은 대상을 끝까지 처리한다")
    void noDeadline() {
        var reservations = mock(DropReservationRepository.class);
        when(reservations.findCancelRefundCandidates(any(), any(), any())).thenReturn(List.of(1L, 2L), List.of(3L), List.of());
        var meters = new SimpleMeterRegistry();
        new DropCancelRefundJob(reservations, mock(PaymentRefundService.class),
                Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC), meters).runOnce();

        // Missing reservations are skipped, so every candidate was visited.
        assertThat(meters.counter("drop.cancel_refund.processed", "result", "skipped").count()).isEqualTo(3);
    }
}
