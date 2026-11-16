package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 행사 생성 API. POST /api/organizer/fan-meetings (주최자·관리자). */
class FanMeetingCreateApiTest extends FanMeetingApiSupport {

    static final String PATH = "/api/organizer/fan-meetings";

    Instant opensAt;
    Instant meetingAt;

    @BeforeEach
    void setUp() {
        opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        meetingAt = opensAt.plus(Duration.ofDays(3));
    }

    @Test
    void 일반_회원은_행사를_만들_수_없다_C41() throws Exception {
        String user = token("USER");

        create(user, body(opensAt, meetingAt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Integer created = jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_events WHERE organizer_id = ?",
                Integer.class, userIdOf(user));
        assertThat(created).isZero();
    }

    @Test
    void 주최자와_관리자는_행사를_만들고_만든_사람이_주최자로_저장된다() throws Exception {
        for (String role : new String[] {"ORGANIZER", "ADMIN"}) {
            String bearer = token(role);
            Long userId = userIdOf(bearer);

            String res = create(bearer, body(opensAt, meetingAt))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.eventId").isNumber())
                    .andReturn().getResponse().getContentAsString();
            Long eventId = ((Number) JsonPath.read(res, "$.eventId")).longValue();

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT organizer_id, status, opens_at_public FROM fan_meeting_events WHERE event_id = ?", eventId);
            assertThat(((Number) row.get("organizer_id")).longValue()).isEqualTo(userId);
            assertThat(row.get("status")).isEqualTo("SCHEDULED");
            assertThat(row.get("opens_at_public")).isEqualTo(true);
        }
    }

    @Test
    void 필수_값이_빠지면_400_INVALID_REQUEST() throws Exception {
        create(token("ORGANIZER"), "{\"title\":\"연말 팬미팅\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 생성_규칙을_어기면_400_INVALID_FAN_MEETING_SETTINGS와_어긴_규칙_메시지() throws Exception {
        Instant tooEarlyMeeting = opensAt.plus(Duration.ofHours(1));

        create(token("ORGANIZER"), body(opensAt, tooEarlyMeeting))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FAN_MEETING_SETTINGS"))
                .andExpect(jsonPath("$.message").value("팬미팅 시각은 응모 마감 48시간 뒤부터 정할 수 있습니다."));
    }

    String body(Instant opensAt, Instant meetingAt) {
        return """
                {"title":"연말 팬미팅","entryOpensAt":"%s","firstNoticeScheduledAt":"%s",
                 "winnerLimit":300,"reserveLimit":100,"feeAmount":50000,
                 "meetingAt":"%s","opensAtPublic":true}
                """.formatted(opensAt, opensAt, meetingAt);
    }

    ResultActions create(String bearer, String json) throws Exception {
        return mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
