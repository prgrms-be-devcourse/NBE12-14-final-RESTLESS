package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.restless.fanmeeting.application.FanMeetingDrawTransitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FanMeetingDrawDeadlineTest extends FanMeetingPromotionSupport {
    @MockitoSpyBean FanMeetingDrawTransitions draws;
    @Autowired MeterRegistry meters;

    @Test
    void lateWorkerRoundsNeverComputeOrAcquireLease(CapturedOutput output) throws Exception {
        Long event = closedEventWithApplicants(1, 1, 2);
        setPromotionClosesAt(event, NOON.plus(Duration.ofHours(1)));
        Long run = jdbc.queryForObject("SELECT draw_run_id FROM fan_meeting_draw_runs WHERE event_id = ?",
                Long.class, event);
        var beforeRun = jdbc.queryForMap("SELECT * FROM fan_meeting_draw_runs WHERE draw_run_id = ?", run);
        var beforeEvent = jdbc.queryForMap("SELECT * FROM fan_meeting_events WHERE event_id = ?", event);
        double beforeMetric = meters.counter("fanmeeting.worker.failure", "action", "draw-too-late").count();

        doReturn(java.util.List.of(run)).when(draws).findDue(anyInt());
        for (int seconds : new int[] {1, 181, 361}) {
            clock.fixed = NOON.plusSeconds(seconds);
            drawWorker.drawDue();
        }

        verify(draws, never()).compute(argThat(claim -> claim.eventId().equals(event)));
        assertThat(jdbc.queryForMap("SELECT * FROM fan_meeting_draw_runs WHERE draw_run_id = ?", run))
                .usingRecursiveComparison().isEqualTo(beforeRun);
        assertThat(jdbc.queryForMap("SELECT * FROM fan_meeting_events WHERE event_id = ?", event))
                .isEqualTo(beforeEvent);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM fan_meeting_draw_runs WHERE draw_run_id = ?",
                Integer.class, run)).isZero();
        assertThat(meters.counter("fanmeeting.worker.failure", "action", "draw-too-late").count())
                .isEqualTo(beforeMetric + 1);
        assertThat(output.getOut().split("FANMEETING_DRAW_TOO_LATE eventId=" + event, -1)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void claimDeadlineBoundary(int offset) throws Exception {
        Long event = closedEventWithApplicants(1, 0, 1);
        setPromotionClosesAt(event, NOON.plus(Duration.ofHours(1)));
        clock.fixed = NOON.plusSeconds(offset);
        Long run = jdbc.queryForObject("SELECT draw_run_id FROM fan_meeting_draw_runs WHERE event_id = ?",
                Long.class, event);

        assertThat(draws.claim(run).isPresent()).isEqualTo(offset <= 0);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM fan_meeting_draw_runs WHERE draw_run_id = ?",
                Integer.class, run)).isEqualTo(offset <= 0 ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void initialOrderMustFitBeforePromotionClose(int offset, CapturedOutput output) throws Exception {
        Long event = closedEventWithApplicants(1, 1, 2);
        clock.fixed = NOON.minusSeconds(1);
        setPromotionClosesAt(event, NOON.plus(Duration.ofHours(1)));
        Long run = jdbc.queryForObject("SELECT draw_run_id FROM fan_meeting_draw_runs WHERE event_id = ?",
                Long.class, event);
        var claim = draws.claim(run).orElseThrow();
        var plan = draws.compute(claim);
        clock.fixed = NOON.plusSeconds(offset);
        var beforeRun = jdbc.queryForMap("SELECT * FROM fan_meeting_draw_runs WHERE draw_run_id = ?", run);
        var beforeEvent = jdbc.queryForMap("SELECT * FROM fan_meeting_events WHERE event_id = ?", event);
        double beforeMetric = meters.counter("fanmeeting.worker.failure", "action", "draw-too-late").count();

        var result = draws.finalizeDraw(claim, plan);

        if (offset > 0) {
            assertThat(result).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_selections WHERE event_id = ?",
                    Integer.class, event)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_orders WHERE event_id = ?",
                    Integer.class, event)).isZero();
            assertThat(jdbc.queryForMap("SELECT * FROM fan_meeting_draw_runs WHERE draw_run_id = ?", run))
                    .usingRecursiveComparison().isEqualTo(beforeRun);
            assertThat(jdbc.queryForMap("SELECT * FROM fan_meeting_events WHERE event_id = ?", event))
                    .isEqualTo(beforeEvent);
            draws.finalizeDraw(claim, plan);
            clock.fixed = clock.instant().plus(Duration.ofMinutes(3));
            assertThat(draws.claim(run)).isEmpty();
            assertThat(jdbc.queryForMap("SELECT * FROM fan_meeting_draw_runs WHERE draw_run_id = ?", run))
                    .usingRecursiveComparison().isEqualTo(beforeRun);
            assertThat(meters.counter("fanmeeting.worker.failure", "action", "draw-too-late").count())
                    .isEqualTo(beforeMetric + 1);
            assertThat(output.getOut().split("FANMEETING_DRAW_TOO_LATE eventId=" + event, -1)).hasSize(2);
            assertThat(eventStatus(event)).isEqualTo("DRAWING");
        } else {
            assertThat(result).hasSize(1);
            assertThat(eventStatus(event)).isEqualTo("PAYMENT_IN_PROGRESS");
        }
    }
}
