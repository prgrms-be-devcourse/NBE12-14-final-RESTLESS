package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.restless.fanmeeting.application.FanMeetingEntryTransitions;
import com.restless.fanmeeting.application.FanMeetingDrawWorker;
import com.restless.fanmeeting.application.FanMeetingEntryScheduler;
import com.restless.fanmeeting.application.FanMeetingExpiryWorker;
import com.restless.fanmeeting.application.FanMeetingPromotionWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class FanMeetingEntryFailureIsolationTest extends FanMeetingApiSupport {
    @MockitoSpyBean FanMeetingEntryTransitions transitions;
    @Autowired FanMeetingDrawWorker draws;
    @Autowired FanMeetingExpiryWorker expiry;
    @Autowired FanMeetingPromotionWorker promotion;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;

    @Test
    void missedOpenFailureStillClosesNormalEventInSameRun() throws Exception {
        Long missed = createEvent(false);
        Long normal = createEvent(true);
        Instant now = clock.instant();
        setWindow(missed, "SCHEDULED", now.minus(Duration.ofMinutes(11)), now.minusSeconds(60));
        setWindow(normal, "OPEN", now.minus(Duration.ofMinutes(11)), now.minusSeconds(60));
        doThrow(new IllegalStateException("lock timeout")).when(transitions).open(missed);
        double before = meters.counter("fanmeeting.worker.failure", "action", "open-missed").count();

        new FanMeetingEntryScheduler(entryWorker, draws, expiry, promotion).run();

        assertThat(jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?",
                String.class, normal)).isEqualTo("CLOSED");
        assertThat(meters.counter("fanmeeting.worker.failure", "action", "open-missed").count()).isEqualTo(before + 1);
    }
}
