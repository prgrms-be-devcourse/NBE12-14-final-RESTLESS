package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** 응모·취소·재응모, 내 응모 상태 (docs/domains/fanmeeting/transactions.md 3·4절). */
class FanMeetingApplicationApiTest extends FanMeetingApiSupport {

    @Autowired TransactionTemplate tx;

    @Test
    void 응모_기간이면_응모되고_APPLIED로_저장된다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");

        apply(fan, eventId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.appliedAt").isString());

        Map<String, Object> row = applicationOf(eventId, fan);
        assertThat(row.get("status")).isEqualTo("APPLIED");
    }

    @Test
    void 이미_응모했으면_409_ALREADY_APPLIED이고_응모는_1건() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());

        apply(fan, eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_APPLIED"));

        assertThat(countApplications(eventId)).isEqualTo(1);
    }

    @Test
    void 오픈_전이면_409_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);

        apply(token("USER"), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
        assertThat(countApplications(eventId)).isZero();
    }

    @Test
    void OPEN이어도_마감_시각이_지났으면_409_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        apply(token("USER"), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
        assertThat(countApplications(eventId)).isZero();
    }

    @Test
    void OPEN이어도_오픈_시각_전이면_409_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.plus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(11)));

        apply(token("USER"), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
    }

    @Test
    void 시각은_응모_기간이어도_아직_OPEN이_아니면_409_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);
        Instant now = Instant.now();
        setWindow(eventId, "SCHEDULED", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(9)));

        apply(token("USER"), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
    }

    @Test
    void 없는_행사에_응모하면_404_FAN_MEETING_NOT_FOUND() throws Exception {
        apply(token("USER"), Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);

        mockMvc.perform(post("/api/fan-meetings/{eventId}/applications", eventId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 응모를_취소하면_WITHDRAWN이_되고_응모_시각은_그대로() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());
        Object appliedAt = applicationOf(eventId, fan).get("applied_at");

        cancel(fan, eventId).andExpect(status().isNoContent());

        Map<String, Object> row = applicationOf(eventId, fan);
        assertThat(row.get("status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("applied_at")).isEqualTo(appliedAt);
    }

    @Test
    void 응모한_적_없거나_이미_취소했으면_409_APPLICATION_NOT_ACTIVE() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");

        cancel(fan, eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ACTIVE"));

        apply(fan, eventId).andExpect(status().isCreated());
        cancel(fan, eventId).andExpect(status().isNoContent());
        cancel(fan, eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_ACTIVE"));
    }

    @Test
    void 응모_기간이_지나면_취소할_수_없다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));

        cancel(fan, eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
        assertThat(applicationOf(eventId, fan).get("status")).isEqualTo("APPLIED");
    }

    @Test
    void 취소한_뒤_재응모하면_같은_행이_APPLIED가_되고_응모_시각이_갱신된다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());
        Map<String, Object> first = applicationOf(eventId, fan);
        cancel(fan, eventId).andExpect(status().isNoContent());

        apply(fan, eventId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        Map<String, Object> again = applicationOf(eventId, fan);
        assertThat(again.get("application_id")).isEqualTo(first.get("application_id"));
        assertThat(again.get("status")).isEqualTo("APPLIED");
        assertThat((java.time.LocalDateTime) again.get("applied_at"))
                .isAfter((java.time.LocalDateTime) first.get("applied_at"));
        assertThat(countApplications(eventId)).isEqualTo(1);
    }

    @Test
    void 내_응모_상태는_응모_전_NONE_응모_후_APPLIED_취소_후_WITHDRAWN() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");

        myApplication(fan, eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"))
                .andExpect(jsonPath("$.appliedAt").value(nullValue()));

        apply(fan, eventId).andExpect(status().isCreated());
        myApplication(fan, eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.appliedAt").isString());

        cancel(fan, eventId).andExpect(status().isNoContent());
        myApplication(fan, eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    @Test
    void 다른_회원의_응모는_내_응모_상태에_보이지_않는다() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        apply(token("USER"), eventId).andExpect(status().isCreated());

        myApplication(token("USER"), eventId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    void 없는_행사의_내_응모_상태는_404_FAN_MEETING_NOT_FOUND() throws Exception {
        myApplication(token("USER"), Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));
    }

    /**
     * 응모 기간은 공유 잠금을 잡은 뒤의 시각으로 확인한다(transactions.md 3절).
     * 마감 전에 들어온 응모가 잠금을 기다리는 사이 마감 시각이 지나면, 마감 Worker가 아직 돌지 않았어도 거절한다.
     */
    @Test
    void 잠금을_기다리는_사이_마감_시각이_지나면_마감_Worker_전이어도_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);
        Instant closesAt = Instant.now().plusSeconds(2);
        setWindow(eventId, "OPEN", closesAt.minus(Duration.ofMinutes(10)), closesAt);
        String fan = token("USER");

        MvcResult result = whileEventLockedPast(eventId, closesAt, () -> apply(fan, eventId).andReturn());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(contentOf(result)).contains("\"APPLICATION_WINDOW_CLOSED\"");
        assertThat(countApplications(eventId)).isZero();
    }

    @Test
    void 취소도_잠금을_기다리는_사이_마감_시각이_지나면_APPLICATION_WINDOW_CLOSED() throws Exception {
        Long eventId = createEvent(true);
        Instant closesAt = Instant.now().plusSeconds(3);
        setWindow(eventId, "OPEN", closesAt.minus(Duration.ofMinutes(10)), closesAt);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());

        MvcResult result = whileEventLockedPast(eventId, closesAt, () -> cancel(fan, eventId).andReturn());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(contentOf(result)).contains("\"APPLICATION_WINDOW_CLOSED\"");
        assertThat(applicationOf(eventId, fan).get("status")).isEqualTo("APPLIED");
    }

    /** 다른 트랜잭션이 행사를 FOR UPDATE로 쥔 동안 요청을 보내고, 마감 시각이 지난 뒤 잠금을 푼다. */
    MvcResult whileEventLockedPast(Long eventId, Instant closesAt, Callable<MvcResult> request) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT event_id FROM fan_meeting_events WHERE event_id = ? FOR UPDATE", eventId);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<MvcResult> late = CompletableFuture.supplyAsync(() -> {
            try {
                return request.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);
        assertThat(Instant.now()).as("요청은 마감 전에 출발했다").isBefore(closesAt);
        while (!Instant.now().isAfter(closesAt.plusMillis(300))) {
            Thread.sleep(50);
        }
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return late.get(10, TimeUnit.SECONDS);
    }

    ResultActions myApplication(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(get("/api/fan-meetings/{eventId}/applications/me", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    Map<String, Object> applicationOf(Long eventId, String bearer) {
        return jdbc.queryForMap("""
                SELECT application_id, status, applied_at FROM fan_meeting_applications
                 WHERE event_id = ? AND user_id = ?
                """, eventId, userIdOf(bearer));
    }
}
