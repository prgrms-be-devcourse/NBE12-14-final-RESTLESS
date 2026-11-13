package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.fanmeeting.application.FanMeetingDrawTransitions;
import com.restless.fanmeeting.application.FanMeetingDrawTransitions.DrawClaim;
import com.restless.fanmeeting.application.FanMeetingDrawWorker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** 추첨 Worker: Claim → 계산 → 최종화 (transactions.md 5절). 스케줄러는 꺼 두고 직접 부른다. */
@ExtendWith(OutputCaptureExtension.class)
class FanMeetingDrawWorkerTest extends FanMeetingApiSupport {

    @Autowired FanMeetingDrawWorker drawWorker;
    @Autowired FanMeetingDrawTransitions draws;

    @Test
    void 추첨하면_당첨은_결제_대기_주문을_받고_예비는_대기하며_행사는_결제_진행으로_간다(CapturedOutput output)
            throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 4);

        drawWorker.drawDue();

        assertThat(selections(eventId, "WINNER")).extracting(s -> s.get("status")).containsOnly("PAYMENT_PENDING");
        assertThat(selections(eventId, "WINNER")).extracting(s -> ((Number) s.get("selection_rank")).intValue())
                .containsExactly(1, 2);
        assertThat(selections(eventId, "RESERVE")).extracting(s -> s.get("status")).containsExactly("WAITING");

        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT status, amount FROM fan_meeting_orders WHERE event_id = ?", eventId);
        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(o -> o.get("status")).containsOnly("PENDING_PAYMENT");
        assertThat(orders).extracting(o -> ((Number) o.get("amount")).longValue()).containsOnly(50_000L);

        Map<String, Object> run = drawRun(eventId);
        assertThat(run.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) run.get("candidate_count")).intValue()).isEqualTo(4);
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(occurrences(output.getAll(), "FANMEETING_NOTIFICATION type=WINNER eventId=" + eventId + " "))
                .isEqualTo(2);
    }

    @Test
    void 응모자가_없으면_추첨은_끝나고_행사는_모집_완료() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 0);

        drawWorker.drawDue();

        assertThat(drawRun(eventId).get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) drawRun(eventId).get("candidate_count")).intValue()).isZero();
        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(selections(eventId, "WINNER")).isEmpty();
    }

    @Test
    void 탈퇴한_응모자는_뽑히지_않고_응모_행은_남는다_C33() throws Exception {
        Long eventId = closedEventWithApplicants(5, 5, 3);
        Long withdrawn = applicantUserIds(eventId).get(0);
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = ? WHERE id = ?", utc(Instant.now()), withdrawn);

        drawWorker.drawDue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_selections WHERE event_id = ? AND user_id = ?",
                Integer.class, eventId, withdrawn)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM fan_meeting_applications WHERE event_id = ? AND user_id = ?",
                String.class, eventId, withdrawn)).isEqualTo("APPLIED");
        assertThat(((Number) drawRun(eventId).get("candidate_count")).intValue()).isEqualTo(2);
    }

    @Test
    void Claim하면_행사는_추첨_중이_되고_lease를_잡는다() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 1);

        Optional<DrawClaim> claim = draws.claim(drawRunId(eventId));

        assertThat(claim).isPresent();
        assertThat(eventStatus(eventId)).isEqualTo("DRAWING");
        Map<String, Object> run = drawRun(eventId);
        assertThat(run.get("status")).isEqualTo("RUNNING");
        assertThat(((Number) run.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(run.get("lease_token")).isNotNull();
    }

    @Test
    void Worker_두_개가_동시에_Claim해도_유효한_token은_하나_C05() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 1);
        Long runId = drawRunId(eventId);

        List<Optional<DrawClaim>> claims = concurrently(2, () -> draws.claim(runId));

        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void lease가_만료된_token으로_늦게_최종화하면_아무것도_저장하지_않는다_C07() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 3);
        Long runId = drawRunId(eventId);
        DrawClaim late = draws.claim(runId).orElseThrow();
        expireLease(runId);
        DrawClaim current = draws.claim(runId).orElseThrow();

        assertThat(draws.finalizeDraw(late, draws.compute(late))).isEmpty();
        assertThat(countSelections(eventId)).isZero();

        assertThat(draws.finalizeDraw(current, draws.compute(current))).hasSize(2);
        assertThat(countSelections(eventId)).isEqualTo(3);
    }

    @Test
    void 같은_추첨을_두_번_최종화해도_두_번째는_저장하지_않고_예외도_없다_C55() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 3);
        DrawClaim claim = draws.claim(drawRunId(eventId)).orElseThrow();
        var result = draws.compute(claim);
        draws.finalizeDraw(claim, result);

        assertThat(draws.finalizeDraw(claim, result)).isEmpty();

        assertThat(countSelections(eventId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_orders WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(2);
    }

    @Test
    void 최종화_중_DB_오류가_나면_전체가_롤백되고_알림도_없다_C08_C36(CapturedOutput output) throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 3);
        Long runId = drawRunId(eventId);
        // 응모 하나에 이미 선발 행을 넣어 두면, 최종화가 같은 응모를 저장할 때 UNIQUE(application_id) 위반이 난다.
        Map<String, Object> app = jdbc.queryForMap(
                "SELECT application_id, user_id FROM fan_meeting_applications WHERE event_id = ? LIMIT 1", eventId);
        jdbc.update("""
                INSERT INTO fan_meeting_selections (event_id, draw_run_id, application_id, user_id, selection_type,
                       selection_rank, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'RESERVE', 99, 'WAITING', ?, ?)
                """, eventId, runId, app.get("application_id"), app.get("user_id"), utc(Instant.now()),
                utc(Instant.now()));

        drawWorker.drawDue();

        assertThat(countSelections(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_orders WHERE event_id = ?",
                Integer.class, eventId)).isZero();
        assertThat(drawRun(eventId).get("status")).isEqualTo("RUNNING");
        assertThat(eventStatus(eventId)).isEqualTo("DRAWING");
        assertThat(occurrences(output.getAll(), "FANMEETING_NOTIFICATION type=WINNER eventId=" + eventId + " "))
                .isZero();
    }

    @Test
    void 다른_회원의_user_id로_선발을_저장하면_복합_FK가_막는다_C25() throws Exception {
        Long eventId = closedEventWithApplicants(2, 1, 1);
        Long runId = drawRunId(eventId);
        Long applicationId = jdbc.queryForObject(
                "SELECT application_id FROM fan_meeting_applications WHERE event_id = ?", Long.class, eventId);
        Long otherUser = userIdOf(token("USER"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO fan_meeting_selections (event_id, draw_run_id, application_id, user_id, selection_type,
                       selection_rank, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'WINNER', 1, 'PAYMENT_PENDING', ?, ?)
                """, eventId, runId, applicationId, otherUser, utc(Instant.now()), utc(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    void expireLease(Long runId) {
        jdbc.update("UPDATE fan_meeting_draw_runs SET lease_until = ? WHERE draw_run_id = ?",
                utc(Instant.now().minusSeconds(1)), runId);
    }

    Long drawRunId(Long eventId) {
        return jdbc.queryForObject("SELECT draw_run_id FROM fan_meeting_draw_runs WHERE event_id = ?", Long.class,
                eventId);
    }

    Map<String, Object> drawRun(Long eventId) {
        return jdbc.queryForMap("""
                SELECT status, candidate_count, attempt_count, lease_token FROM fan_meeting_draw_runs
                 WHERE event_id = ?
                """, eventId);
    }

    String eventStatus(Long eventId) {
        return jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?", String.class, eventId);
    }

    List<Map<String, Object>> selections(Long eventId, String type) {
        return jdbc.queryForList("""
                SELECT status, selection_rank FROM fan_meeting_selections
                 WHERE event_id = ? AND selection_type = ? ORDER BY selection_rank
                """, eventId, type);
    }

    int countSelections(Long eventId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_selections WHERE event_id = ?",
                Integer.class, eventId);
        return n == null ? 0 : n;
    }

    List<Long> applicantUserIds(Long eventId) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT user_id FROM fan_meeting_applications WHERE event_id = ? ORDER BY application_id",
                Long.class, eventId));
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
