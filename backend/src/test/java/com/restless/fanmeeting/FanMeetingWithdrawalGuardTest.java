package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.fanmeeting.application.FanMeetingDrawWorker;
import com.restless.fanmeeting.application.FanMeetingWithdrawalGuard;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 팬미팅 탈퇴 확인 (policy.md 5절). 진행 중인 주문이 있으면 회원 탈퇴를 막는다.
 * 결제 대기·보류·환불 대기 주문이 있거나, 결제 완료인데 팬미팅이 아직 안 지났으면 true.
 */
class FanMeetingWithdrawalGuardTest extends FanMeetingApiSupport {

    @Autowired FanMeetingDrawWorker drawWorker;
    @Autowired FanMeetingWithdrawalGuard guard;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void 결제_대기_주문이_있는_당첨자는_탈퇴할_수_없다() throws Exception {
        Long winner = drawnWinner();

        assertThat(guard.hasActiveParticipation(winner)).isTrue();
    }

    @Test
    void 결제_결과를_기다리는_보류_주문이나_환불_대기_주문이_있어도_탈퇴할_수_없다() throws Exception {
        Long winner = drawnWinner();

        setOrderStatus(winner, "PAYMENT_REVIEW");
        assertThat(guard.hasActiveParticipation(winner)).isTrue();

        setOrderStatus(winner, "REFUND_PENDING");
        assertThat(guard.hasActiveParticipation(winner)).isTrue();
    }

    @Test
    void 결제_완료면_팬미팅_전까지는_탈퇴할_수_없고_지나면_탈퇴할_수_있다() throws Exception {
        Long winner = drawnWinner();
        setOrderStatus(winner, "PAID");

        assertThat(guard.hasActiveParticipation(winner)).isTrue();

        jdbc.update("""
                UPDATE fan_meeting_events SET meeting_at = ?
                 WHERE event_id = (SELECT event_id FROM fan_meeting_orders WHERE user_id = ?)
                """, utc(Instant.now().minus(Duration.ofMinutes(1))), winner);
        assertThat(guard.hasActiveParticipation(winner)).isFalse();
    }

    @Test
    void 끝난_주문만_있거나_주문이_없으면_탈퇴할_수_있다() throws Exception {
        Long winner = drawnWinner();
        setOrderStatus(winner, "EXPIRED");

        assertThat(guard.hasActiveParticipation(winner)).isFalse();
        assertThat(guard.hasActiveParticipation(userIdOf(token("USER")))).isFalse();
    }

    @Test
    void 예비자는_주문이_없어_탈퇴할_수_있다() throws Exception {
        Long eventId = closedEventWithApplicants(1, 1, 2);
        drawWorker.drawDue();
        Long reserve = jdbc.queryForObject("""
                SELECT user_id FROM fan_meeting_selections WHERE event_id = ? AND selection_type = 'RESERVE'
                """, Long.class, eventId);

        assertThat(guard.hasActiveParticipation(reserve)).isFalse();
    }

    @Test
    void 회원_탈퇴_API도_이_확인을_거쳐_409_MEMBER_HAS_ACTIVE_PARTICIPATION() throws Exception {
        Long winner = drawnWinner();
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode("password1!"), winner);
        String bearer = "Bearer " + tokenProvider.issue(winner, "USER").value();

        mockMvc.perform(post("/api/me/withdraw").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"password1!\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_HAS_ACTIVE_PARTICIPATION"));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, winner))
                .isEqualTo("ACTIVE");
    }

    /** 응모자 1명을 당첨시켜 결제 대기 주문을 만든다. */
    Long drawnWinner() throws Exception {
        Long eventId = closedEventWithApplicants(1, 0, 1);
        drawWorker.drawDue();
        return jdbc.queryForObject("SELECT user_id FROM fan_meeting_orders WHERE event_id = ?", Long.class, eventId);
    }

    void setOrderStatus(Long userId, String status) {
        jdbc.update("UPDATE fan_meeting_orders SET status = ? WHERE user_id = ?", status, userId);
    }
}
