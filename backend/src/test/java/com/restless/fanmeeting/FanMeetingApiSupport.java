package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.fanmeeting.application.FanMeetingEntryWorker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 팬미팅 API 테스트 공통: 회원 토큰, 행사 만들기, 행사 상태·시각 바꾸기, 응모·취소, 동시 요청. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class FanMeetingApiSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired FanMeetingEntryWorker entryWorker;

    String token(String role) {
        String email = "fan-meeting-" + UUID.randomUUID() + "@example.com";
        Timestamp t = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', ?, ?, 'ACTIVE', ?, ?)
                """, email, role, role, t, t);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + tokenProvider.issue(id, role).value();
    }

    Long userIdOf(String bearer) {
        return Long.valueOf(tokenProvider.decoder().decode(bearer.substring("Bearer ".length())).getSubject());
    }

    /** 주최자 API로 행사를 만든다. 오픈은 1시간 뒤, 팬미팅은 3일 뒤. 당첨 300명, 예비 100명. */
    Long createEvent(boolean opensAtPublic) throws Exception {
        return createEvent(opensAtPublic, 300, 100);
    }

    Long createEvent(boolean opensAtPublic, int winnerLimit, int reserveLimit) throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"title":"연말 팬미팅","entryOpensAt":"%s","firstNoticeScheduledAt":"%s",
                 "winnerLimit":%d,"reserveLimit":%d,"feeAmount":50000,
                 "meetingAt":"%s","opensAtPublic":%s}
                """.formatted(opensAt, opensAt, winnerLimit, reserveLimit, opensAt.plus(Duration.ofDays(3)),
                opensAtPublic);
        String res = mockMvc.perform(post("/api/organizer/fan-meetings")
                        .header(HttpHeaders.AUTHORIZATION, token("ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.eventId")).longValue();
    }

    /** Open Worker 대신 행사를 연다. 오픈은 1분 전, 마감은 9분 뒤. */
    void open(Long eventId) {
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofMinutes(9)));
    }

    /** 엔티티는 UTC로 저장한다(TIMESTAMP_UTC). JVM 시간대와 무관하게 UTC 벽시계 값으로 넣는다. */
    void setWindow(Long eventId, String status, Instant opensAt, Instant closesAt) {
        jdbc.update("UPDATE fan_meeting_events SET status = ?, entry_opens_at = ?, entry_closes_at = ? WHERE event_id = ?",
                status, utc(opensAt), utc(closesAt), eventId);
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 응모를 받은 뒤 마감 Worker로 닫는다. 마감이 PENDING 추첨 실행을 만든다. */
    Long closedEventWithApplicants(int winnerLimit, int reserveLimit, int applicants) throws Exception {
        Long eventId = createEvent(true, winnerLimit, reserveLimit);
        open(eventId);
        for (int i = 0; i < applicants; i++) {
            apply(token("USER"), eventId).andExpect(status().isCreated());
        }
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));
        entryWorker.closeDue();
        assertThat(jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?", String.class,
                eventId)).isEqualTo("CLOSED");
        return eventId;
    }

    /** 응모(재응모 포함). */
    ResultActions apply(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(post("/api/fan-meetings/{eventId}/applications", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** 응모 취소. */
    ResultActions cancel(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(delete("/api/fan-meetings/{eventId}/applications/me", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    int countApplications(Long eventId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_applications WHERE event_id = ?",
                Integer.class, eventId);
        return n == null ? 0 : n;
    }

    /** requests개를 동시에 출발시켜 결과를 모은다. 서버 여러 대·따닥 요청을 흉내 낸다. */
    static <T> List<T> concurrently(int requests, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        return results;
    }

    static String contentOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 다른 스레드가 신호를 줄 때까지(최대 60초) 기다린다. 잠금을 쥔 트랜잭션을 붙잡아 둘 때 쓴다. */
    static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
