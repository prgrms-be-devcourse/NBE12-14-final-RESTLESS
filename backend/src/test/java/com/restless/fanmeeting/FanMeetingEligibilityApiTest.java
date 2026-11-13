package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.fanmeeting.application.MemberPort;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 가입 시각 검사 (transactions.md 3절). 설정을 켠 상태로 돌린다(기본은 꺼짐).
 * 공지 시각 이후에 가입한 회원은 응모할 수 없다. 공지를 보고 급히 만든 계정을 막는다.
 */
@TestPropertySource(properties = "restless.fanmeeting.eligibility.joined-at-check.enabled=true")
class FanMeetingEligibilityApiTest extends FanMeetingApiSupport {

    @MockitoSpyBean MemberPort memberPort;

    @Test
    void 공지_시각_이후에_가입한_회원은_403_APPLICATION_NOT_ELIGIBLE_C21() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        noticeAt(eventId, Instant.now().minus(Duration.ofHours(1)));
        String newcomer = token("USER");

        apply(newcomer, eventId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ELIGIBLE"));
        assertThat(countApplications(eventId)).isZero();
    }

    @Test
    void 공지_시각과_같은_순간에_가입해도_응모할_수_없다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        Instant noticeAt = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        noticeAt(eventId, noticeAt);
        String member = token("USER");
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", utc(noticeAt), userIdOf(member));

        apply(member, eventId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ELIGIBLE"));
    }

    @Test
    void 공지_시각_전에_가입한_회원은_응모할_수_있다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        noticeAt(eventId, Instant.now().minus(Duration.ofHours(1)));
        String member = token("USER");
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                utc(Instant.now().minus(Duration.ofHours(2))), userIdOf(member));

        apply(member, eventId).andExpect(status().isCreated());
    }

    @Test
    void 가입_시각_조회가_실패하면_503_MEMBER_LOOKUP_FAILED이고_응모는_0건_C50() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        doThrow(new IllegalStateException("회원 모듈 응답 없음")).when(memberPort).joinedAt(anyLong());

        apply(token("USER"), eventId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMBER_LOOKUP_FAILED"));
        assertThat(countApplications(eventId)).isZero();
    }

    void noticeAt(Long eventId, Instant noticeAt) {
        jdbc.update("UPDATE fan_meeting_events SET first_notice_scheduled_at = ? WHERE event_id = ?",
                utc(noticeAt), eventId);
    }
}
