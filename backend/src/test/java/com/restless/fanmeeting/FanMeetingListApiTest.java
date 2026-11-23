package com.restless.fanmeeting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

class FanMeetingListApiTest extends FanMeetingApiSupport {
    @Test
    void publicListIsNewestFirstAndSharesDetailVisibility() throws Exception {
        Long first = createEvent(true);
        Long last = createEvent(false);
        String body = mockMvc.perform(get("/api/fan-meetings").param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.items[*].eventId");
        assertThat(ids.stream().map(Number::longValue).toList()).contains(first, last).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        Number firstId = ids.getFirst();
        assertThat(firstId.longValue()).isEqualTo(last);
        mockMvc.perform(get("/api/fan-meetings").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].entryOpensAt").isEmpty());
        mockMvc.perform(get("/api/fan-meetings/{id}",last))
                .andExpect(jsonPath("$.entryOpensAt").isEmpty());
        open(last);
        mockMvc.perform(get("/api/fan-meetings").param("status","OPEN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].entryOpensAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entryClosesAt").isNotEmpty());
        mockMvc.perform(get("/api/fan-meetings").param("page","999999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void lastPageAndVisibilityMatchDetail() throws Exception {
        for (boolean announced : new boolean[] {false, true}) {
            for (String state : List.of("SCHEDULED", "OPEN", "CLOSED")) {
                Long id = createEvent(announced);
                jdbc.update("UPDATE fan_meeting_events SET status=? WHERE event_id=?", state, id);
                String detail = mockMvc.perform(get("/api/fan-meetings/{id}", id)).andReturn().getResponse().getContentAsString();
                String list = mockMvc.perform(get("/api/fan-meetings").param("status", state))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(((Number) JsonPath.read(list, "$.items[0].eventId")).longValue()).isEqualTo(id);
                for (String field : List.of("entryOpensAt", "entryClosesAt")) {
                    Object expected = JsonPath.read(detail, "$." + field);
                    Object actual = JsonPath.read(list, "$.items[0]." + field);
                    assertThat(actual).as(state + " " + field).isEqualTo(expected);
                }
            }
        }
        String page = mockMvc.perform(get("/api/fan-meetings").param("size","1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int pages = ((Number) JsonPath.read(page, "$.totalPages")).intValue();
        mockMvc.perform(get("/api/fan-meetings").param("size","1").param("page",Integer.toString(pages-1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/fan-meetings").param("size","1").param("page",Integer.toString(pages)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void defaultListOnlyContainsScheduledAndOpenAndCancelledIsNeverListed() throws Exception {
        Long scheduled = createEvent(true);
        Long opened = createEvent(true);
        Long closed = createEvent(true);
        Long cancelled = createEvent(true);
        jdbc.update("UPDATE fan_meeting_events SET status='OPEN' WHERE event_id=?", opened);
        jdbc.update("UPDATE fan_meeting_events SET status='CLOSED' WHERE event_id=?", closed);
        jdbc.update("UPDATE fan_meeting_events SET status='CANCELLED' WHERE event_id=?", cancelled);
        String body = mockMvc.perform(get("/api/fan-meetings").param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.items[*].eventId");
        assertThat(ids.stream().map(Number::longValue).toList())
                .contains(scheduled, opened).doesNotContain(closed, cancelled);
        mockMvc.perform(get("/api/fan-meetings").param("status", "CANCELLED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/fan-meetings/{id}", cancelled))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void invalidPageParametersAreBadRequests() throws Exception {
        for (String[] param : new String[][] {{"page","-1"},{"page","x"},{"size","0"},{"size","101"},{"status","INVALID"}}) {
            mockMvc.perform(get("/api/fan-meetings").param(param[0],param[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }
}
