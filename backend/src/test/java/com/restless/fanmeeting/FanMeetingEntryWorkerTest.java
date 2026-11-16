package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.fanmeeting.application.FanMeetingEntryTransitions;
import com.restless.fanmeeting.application.FanMeetingEntryTransitions.OpenResult;
import com.restless.fanmeeting.application.FanMeetingEntryWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Open / Close Worker (transactions.md 2절). 스케줄러는 테스트에서 꺼져 있어 워커를 직접 부른다. */
@ExtendWith(OutputCaptureExtension.class)
class FanMeetingEntryWorkerTest extends FanMeetingApiSupport {

    @Autowired FanMeetingEntryWorker worker;
    @Autowired FanMeetingEntryTransitions transitions;

    @Test
    void 오픈_시각이_되면_OPEN으로_바꾼다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "SCHEDULED", now.minusSeconds(1), now.plus(Duration.ofMinutes(10)));

        worker.openDue();

        assertThat(statusOf(eventId)).isEqualTo("OPEN");
    }

    @Test
    void 오픈_시각_전이면_그대로_둔다() throws Exception {
        Long eventId = createEvent(false);

        worker.openDue();

        assertThat(statusOf(eventId)).isEqualTo("SCHEDULED");
    }

    @Test
    void 마감_시각까지_지났으면_열지_않고_OPEN_MISSED를_행사당_한_번만_남긴다(CapturedOutput output) throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "SCHEDULED", now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(10)));

        worker.openDue();
        worker.openDue();

        assertThat(statusOf(eventId)).isEqualTo("SCHEDULED");
        assertThat(occurrences(output.getAll(), "FANMEETING_OPEN_MISSED eventId=" + eventId + " ")).isEqualTo(1);
    }

    @Test
    void 마감_시각이_되면_CLOSED로_바꾸고_추첨_실행을_PENDING으로_만든다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        worker.closeDue();

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT status, closed_at FROM fan_meeting_events WHERE event_id = ?", eventId);
        assertThat(event.get("status")).isEqualTo("CLOSED");
        assertThat(event.get("closed_at")).isNotNull();

        Map<String, Object> run = jdbc.queryForMap("""
                SELECT status, LENGTH(random_seed) AS seed_bytes, algorithm_version, attempt_count
                  FROM fan_meeting_draw_runs WHERE event_id = ?
                """, eventId);
        assertThat(run.get("status")).isEqualTo("PENDING");
        assertThat(((Number) run.get("seed_bytes")).intValue()).isEqualTo(32);
        assertThat(run.get("algorithm_version")).isEqualTo("HMAC_SHA256_RANK_V1");
        assertThat(((Number) run.get("attempt_count")).intValue()).isZero();
    }

    @Test
    void 마감_전이면_그대로_두고_추첨_실행도_만들지_않는다() throws Exception {
        Long eventId = createEvent(false);
        open(eventId);

        worker.closeDue();

        assertThat(statusOf(eventId)).isEqualTo("OPEN");
        assertThat(drawRunCount(eventId)).isZero();
    }

    @Test
    void 마감을_두_번_돌려도_추첨_실행은_하나() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        worker.closeDue();
        worker.closeDue();

        assertThat(drawRunCount(eventId)).isEqualTo(1);
    }

    @Test
    void 서버_두_대가_같은_행사를_동시에_열어도_한_번만_연다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "SCHEDULED", now.minusSeconds(1), now.plus(Duration.ofMinutes(10)));

        List<OpenResult> results = concurrently(2, () -> transitions.open(eventId));

        assertThat(results).containsExactlyInAnyOrder(OpenResult.OPENED, OpenResult.SKIPPED);
        assertThat(statusOf(eventId)).isEqualTo("OPEN");
    }

    @Test
    void 서버_두_대가_같은_행사를_동시에_닫아도_한_번만_닫히고_추첨_실행은_하나() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        List<Boolean> closed = concurrently(2, () -> transitions.close(eventId));

        assertThat(closed).containsExactlyInAnyOrder(true, false);
        assertThat(drawRunCount(eventId)).isEqualTo(1);
    }

    @Test
    void 행사마다_seed가_다르다() throws Exception {
        Instant now = Instant.now();
        Long first = createEvent(false);
        Long second = createEvent(false);
        setWindow(first, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));
        setWindow(second, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        worker.closeDue();

        assertThat(seedOf(first)).isNotEqualTo(seedOf(second));
    }

    String statusOf(Long eventId) {
        return jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?", String.class, eventId);
    }

    int drawRunCount(Long eventId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_draw_runs WHERE event_id = ?",
                Integer.class, eventId);
        return n == null ? 0 : n;
    }

    String seedOf(Long eventId) {
        return jdbc.queryForObject("SELECT HEX(random_seed) FROM fan_meeting_draw_runs WHERE event_id = ?",
                String.class, eventId);
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
