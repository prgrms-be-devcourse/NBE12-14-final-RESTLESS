package com.restless.fanmeeting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FanMeetingEditApiTest extends FanMeetingPromotionSupport {
    static String body(Instant opens, String title) {
        return """
                {"title":"%s","entryOpensAt":"%s","firstNoticeScheduledAt":"%s","winnerLimit":2,
                "reserveLimit":1,"feeAmount":3000,"meetingAt":"%s","opensAtPublic":true}
                """.formatted(title, opens, opens, opens.plus(Duration.ofDays(3)));
    }
    String owner(Long id) {
        return "Bearer " + tokenProvider.issue(jdbc.queryForObject("SELECT organizer_id FROM fan_meeting_events WHERE event_id = ?",Long.class,id), "ORGANIZER").value();
    }
    @Test
    void ownerAndAdminUpdateAndWorkerUsesNewTime() throws Exception {
        Long id=createEvent(true);
        clock.fixed=NOON;
        setWindow(id,"SCHEDULED",NOON.plusSeconds(60),NOON.plusSeconds(660));
        Instant next=NOON.plus(Duration.ofHours(2));
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner(id))
                .contentType("application/json").content(body(next,"수정")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정"));
        mockMvc.perform(get("/api/fan-meetings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정"))
                .andExpect(jsonPath("$.feeAmount").value(3000))
                .andExpect(jsonPath("$.winnerLimit").value(2));
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",token("ADMIN"))
                .contentType("application/json").content(body(next,"관리자수정"))).andExpect(status().isOk());
        clock.fixed=NOON.plusSeconds(60); entryWorker.openDue();
        assertThat(eventStatus(id)).isEqualTo("SCHEDULED");
        clock.fixed=next; entryWorker.openDue();
        assertThat(eventStatus(id)).isEqualTo("OPEN");
    }
    @ParameterizedTest
    @ValueSource(ints={-1,0,1})
    void originalOpeningTimeBoundary(int offset) throws Exception {
        Long id=createEvent(true);
        clock.fixed=NOON.plusSeconds(offset);
        setWindow(id,"SCHEDULED",NOON,NOON.plusSeconds(600));
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner(id))
                .contentType("application/json").content(body(NOON.plusSeconds(3600),"새 행사")))
                .andExpect(status().is(offset<0?200:409));
    }
    @Test
    void permissionsValidationAndStates() throws Exception {
        Long id=createEvent(true);
        for(String role:new String[]{"ORGANIZER","USER"}) {
            mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",token(role))
                    .contentType("application/json").content(body(clock.instant().plusSeconds(7200),"권한")))
                    .andExpect(status().is(role.equals("ORGANIZER") ? 404 : 403));
        }
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).contentType("application/json")
                .content(body(clock.instant().plusSeconds(7200),"권한"))).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner(id))
                .contentType("application/json").content(body(clock.instant().minusSeconds(1),"검증")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FAN_MEETING_SETTINGS"));
        open(id);
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner(id))
                .contentType("application/json").content(body(clock.instant().plusSeconds(7200),"열림")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_EDITABLE"));
    }
}
