package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FanMeetingInvariantCheckerTest extends FanMeetingOrderApiSupport {
    @Test
    void applicationUniquenessBlockReportsZero() throws Exception {
        Long event = closedEventWithApplicants(1, 0, 1);
        assertThat(FanMeetingInvariants.violations(jdbc, List.of(event), false))
                .containsEntry("INV-FM-01 행사·회원당 응모 최대 1개", 0L);
        FanMeetingInvariants.assertHolds(jdbc, List.of(event), "unique application");
    }

    @Test
    void sqlFileExecutesWithoutTestSubstitution() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("harness/fanmeeting-invariants.sql"));
            return null;
        });
    }

    @Test
    void eligibilityCheckIsExplicitAndIncludesEquality() throws Exception {
        Long event = closedEventWithApplicants(1, 0, 1);
        for (int seconds : new int[] {-1, 0, 1}) {
            jdbc.update("""
                    UPDATE users u JOIN fan_meeting_applications a ON a.user_id = u.id
                    JOIN fan_meeting_events e ON e.event_id = a.event_id
                    SET u.created_at = TIMESTAMPADD(SECOND, ?, e.first_notice_scheduled_at)
                    WHERE a.event_id = ?
                    """, seconds, event);
            assertThat(FanMeetingInvariants.violations(jdbc, List.of(event), false).get("INV-FM-03 가입 시각 검사 ON"))
                    .isZero();
            assertThat(FanMeetingInvariants.violations(jdbc, List.of(event), true).get("INV-FM-03 가입 시각 검사 ON"))
                    .isEqualTo(seconds < 0 ? 0 : 1);
        }
    }

    @Test
    void closeTimestampBoundaries() throws Exception {
        Long event = closedEventWithApplicants(1, 0, 1);
        for (int seconds : new int[] {-1, 0, 1}) {
            jdbc.update("""
                    UPDATE fan_meeting_applications a JOIN fan_meeting_events e ON e.event_id = a.event_id
                    SET a.updated_at = TIMESTAMPADD(SECOND, ?, e.closed_at) WHERE a.event_id = ?
                    """, seconds, event);
            assertThat(FanMeetingInvariants.violations(jdbc, List.of(event), false).get("INV-FM-02 마감 뒤 응모 수정"))
                    .isEqualTo(seconds > 0 ? 1 : 0);
        }
    }

    @Test
    void everyRequiredOrderAndOptionalOrderState() throws Exception {
        WinnerOrder order = winnerOrder();
        jdbc.update("DELETE FROM fan_meeting_orders WHERE order_id = ?", order.orderId());
        for (String state : List.of("PAYMENT_PENDING", "CONFIRMED", "EXPIRED", "DECLINED", "WAITING", "NOT_NEEDED")) {
            jdbc.update("UPDATE fan_meeting_selections SET status = ? WHERE event_id = ?", state, order.eventId());
            assertThat(FanMeetingInvariants.violations(jdbc, List.of(order.eventId()), false)
                    .get("INV-FM-09 선발–주문 짝 표에 없는 조합"))
                    .as(state).isEqualTo(List.of("WAITING", "NOT_NEEDED").contains(state) ? 0 : 1);
        }
    }

    @Test
    void tooManyReservesIsViolation() throws Exception {
        Long event = closedEventWithApplicants(1, 2, 3);
        drawWorker.drawDue();
        jdbc.update("UPDATE fan_meeting_events SET reserve_limit = 1 WHERE event_id = ?", event);
        assertThat(FanMeetingInvariants.violations(jdbc, List.of(event), false)
                .get("INV-FM-06 선발 유형별 인원 상한")).isEqualTo(1);
    }

    @Test
    void missingRequiredOrderIsViolation() throws Exception {
        WinnerOrder order = winnerOrder();
        jdbc.update("DELETE FROM fan_meeting_orders WHERE order_id = ?", order.orderId());
        jdbc.update("UPDATE fan_meeting_selections SET status = 'CONFIRMED' WHERE event_id = ?", order.eventId());
        assertThatThrownBy(() -> FanMeetingInvariants.assertHolds(jdbc, List.of(order.eventId()), "missing"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("INV-FM-09");
    }

    @Test
    void tooManyWinnersIsViolationEvenAfterExpiry() throws Exception {
        Long event = closedEventWithApplicants(2, 0, 2);
        drawWorker.drawDue();
        jdbc.update("UPDATE fan_meeting_selections SET status = 'EXPIRED' WHERE event_id = ?", event);
        jdbc.update("UPDATE fan_meeting_orders SET status = 'EXPIRED' WHERE event_id = ?", event);
        jdbc.update("UPDATE fan_meeting_events SET winner_limit = 1 WHERE event_id = ?", event);
        assertThatThrownBy(() -> FanMeetingInvariants.assertHolds(jdbc, List.of(event), "winner count"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("INV-FM-06");
    }

    @Test
    void changedApplicationAfterCloseIsViolation() throws Exception {
        Long event = closedEventWithApplicants(1, 0, 1);
        jdbc.update("""
                UPDATE fan_meeting_applications a JOIN fan_meeting_events e ON e.event_id = a.event_id
                SET a.updated_at = e.closed_at + INTERVAL 1 SECOND WHERE a.event_id = ?
                """, event);
        assertThatThrownBy(() -> FanMeetingInvariants.assertHolds(jdbc, List.of(event), "late update"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("INV-FM-02");
    }

    @Test
    void normalDataAndOtherEventsAreIsolated() throws Exception {
        WinnerOrder good = winnerOrder();
        WinnerOrder bad = winnerOrder();
        jdbc.update("DELETE FROM fan_meeting_orders WHERE order_id = ?", bad.orderId());
        FanMeetingInvariants.assertHolds(jdbc, List.of(good.eventId()), "normal");
        FanMeetingInvariants.assertHolds(jdbc, List.of(), "empty scope");
    }
}
