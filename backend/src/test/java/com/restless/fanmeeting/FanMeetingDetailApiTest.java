package com.restless.fanmeeting;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** 행사 상세 GET /api/fan-meetings/{eventId} (공개). 오픈 시각 공개 여부(opens_at_public). */
class FanMeetingDetailApiTest extends FanMeetingApiSupport {

    @Test
    void 게릴라_오픈은_OPEN_전에_오픈_시각과_마감_시각을_숨긴다() throws Exception {
        Long eventId = createEvent(false);

        detail(eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.title").value("연말 팬미팅"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.entryOpensAt").value(nullValue()))
                .andExpect(jsonPath("$.entryClosesAt").value(nullValue()));
    }

    @Test
    void 예고_오픈은_OPEN_전에도_오픈_시각을_보여_주고_마감_시각은_숨긴다() throws Exception {
        Long eventId = createEvent(true);

        detail(eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.entryOpensAt").isString())
                .andExpect(jsonPath("$.entryClosesAt").value(nullValue()));
    }

    @Test
    void OPEN_중에는_게릴라여도_오픈_시각과_마감_시각을_보여_준다() throws Exception {
        Long eventId = createEvent(false);
        open(eventId);

        detail(eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.entryOpensAt").isString())
                .andExpect(jsonPath("$.entryClosesAt").isString());
    }

    @Test
    void CLOSED_뒤에는_마감_시각을_숨긴다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "CLOSED", now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(10)));

        detail(eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.entryOpensAt").isString())
                .andExpect(jsonPath("$.entryClosesAt").value(nullValue()));
    }

    @Test
    void 없는_행사는_404_FAN_MEETING_NOT_FOUND() throws Exception {
        detail(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));
    }

    ResultActions detail(Long eventId) throws Exception {
        return mockMvc.perform(get("/api/fan-meetings/{eventId}", eventId));
    }
}
