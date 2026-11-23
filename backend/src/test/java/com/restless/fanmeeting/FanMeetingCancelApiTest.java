package com.restless.fanmeeting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(OutputCaptureExtension.class)
class FanMeetingCancelApiTest extends FanMeetingPromotionSupport {
    String owner(Long id) {
        return "Bearer " + tokenProvider.issue(jdbc.queryForObject("SELECT organizer_id FROM fan_meeting_events WHERE event_id = ?",Long.class,id), "ORGANIZER").value();
    }
    @Test
    void cancelIsIdempotentHiddenAndCannotOpenOrApply(CapturedOutput output) throws Exception {
        Long id=createEvent(false);
        String owner=owner(id);
        for(int repeat=0;repeat<2;repeat++) {
            mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id).header("Authorization",owner))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/fan-meetings/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.entryOpensAt").isEmpty());
        String list=mockMvc.perform(get("/api/fan-meetings").param("size","100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids=com.jayway.jsonpath.JsonPath.read(list,"$.items[*].eventId");
        assertThat(ids.stream().map(Number::longValue).toList()).doesNotContain(id);
        mockMvc.perform(get("/api/fan-meetings").param("status","CANCELLED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        apply(token("USER"),id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
        clock.fixed=NOON.plus(Duration.ofDays(4));
        entryWorker.openDue();
        assertThat(eventStatus(id)).isEqualTo("CANCELLED");
        assertThat(output.getAll()).doesNotContain("FANMEETING_OPEN_MISSED eventId="+id+" ");
        FanMeetingInvariants.assertHolds(jdbc,List.of(id),"cancel");
    }
    @ParameterizedTest
    @ValueSource(ints={-1,0,1})
    void openingBoundary(int offset) throws Exception {
        Long id=createEvent(true);
        setWindow(id,"SCHEDULED",NOON,NOON.plusSeconds(600));
        clock.fixed=NOON.plusSeconds(offset);
        mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id).header("Authorization",owner(id)))
                .andExpect(status().is(offset<0?200:409));
    }
    @Test
    void cancellationPermissionsAndOpenState() throws Exception {
        Long id=createEvent(true);
        for(String role:new String[]{"ORGANIZER","USER"}) {
            mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id).header("Authorization",token(role)))
                    .andExpect(status().is(role.equals("ORGANIZER") ? 404 : 403));
        }
        mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id).header("Authorization",token("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner(id))
                .contentType("application/json").content(FanMeetingEditApiTest.body(clock.instant().plusSeconds(7200),"취소")))
                .andExpect(status().isConflict());
        Long opened=createEvent(true); open(opened);
        mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",opened).header("Authorization",owner(opened)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_EDITABLE"));
    }
    @Test
    void cancelledInvariantDetectsApplication() throws Exception {
        Long id=createEvent(true);
        jdbc.update("UPDATE fan_meeting_events SET status='CANCELLED' WHERE event_id=?",id);
        Long user=jdbc.queryForObject("SELECT organizer_id FROM fan_meeting_events WHERE event_id=?",Long.class,id);
        jdbc.update("""
                INSERT INTO fan_meeting_applications(event_id,user_id,status,applied_at,updated_at)
                VALUES (?,?,'APPLIED',?,?)
                """,id,user,utc(clock.instant()),utc(clock.instant()));
        assertThat(FanMeetingInvariants.violations(jdbc,List.of(id),false))
                .containsEntry("INV-FM-18 취소 행사에 하위 데이터 없음",1L);
    }
}
