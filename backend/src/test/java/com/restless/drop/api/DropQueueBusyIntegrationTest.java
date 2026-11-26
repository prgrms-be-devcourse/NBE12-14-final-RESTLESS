package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.DropQueueAdmissionJob;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import com.restless.drop.application.dto.CreateDropRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 대기열 경로의 pollAfterMs와 동시 처리 상한을 실제 컨트롤러 경로로 확인한다 (#390). */
@SpringBootTest(properties = {"restless.ratelimit.enabled=false", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.required=true",
        "restless.drop.queue.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("대기열 pollAfterMs와 DROP_QUEUE_BUSY (#390)")
class DropQueueBusyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropQueueAdmissionJob admission;
    @Autowired DropQueueConcurrencyLimiter limiter;
    @Autowired MeterRegistry registry;
    private final List<Kind> held = new ArrayList<>();
    private Long user;
    private Long drop;
    private Long variant;

    @BeforeEach
    void createDropAndUser() {
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        String unique = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'busy', 'USER', 'ACTIVE', ?, ?)
                """, unique + "@busy.test", timestamp, timestamp);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, unique + "@busy.test");
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                unique, timestamp);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name=?", Long.class, unique);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, unique, timestamp);
        Long concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title=?", Long.class, unique);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now, now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 1000L, 3)))).dropId();
        admin.publish(drop);
        variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id=?", Long.class, drop);
    }

    @AfterEach
    void releaseHeldPermits() {
        held.forEach(limiter::release);
        held.clear();
    }

    private void exhaust(Kind kind) {
        while (limiter.tryAcquire(kind)) held.add(kind);
    }

    private ResultActions enter() throws Exception {
        return mvc.perform(post("/api/drops/{dropId}/queue/entries", drop)
                .with(jwt().jwt(token -> token.subject(user.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":" + variant + "}"));
    }

    private ResultActions me() throws Exception {
        return mvc.perform(get("/api/drops/{dropId}/queue/me", drop)
                .with(jwt().jwt(token -> token.subject(user.toString()))));
    }

    @Test
    @DisplayName("WAITING 진입과 상태 조회 응답에 pollAfterMs가 있고, 입장 허가 뒤에는 없다")
    void pollAfterMsOnlyWhileWaiting() throws Exception {
        enter().andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1)).andExpect(jsonPath("$.pollAfterMs").value(1000));
        enter().andExpect(status().isOk()).andExpect(jsonPath("$.pollAfterMs").value(1000));
        me().andExpect(status().isOk()).andExpect(jsonPath("$.pollAfterMs").value(1000));

        admission.runOnce();
        me().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.pollAfterMs").doesNotExist());
        enter().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.pollAfterMs").doesNotExist());
    }

    @Test
    @DisplayName("진입 허용치가 모두 차 있으면 즉시 503 DROP_QUEUE_BUSY와 Retry-After를 주고, 행을 만들지 않는다")
    void entryAboveLimitIsRejected() throws Exception {
        exhaust(Kind.ENTRY);
        enter().andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("DROP_QUEUE_BUSY")).andExpect(jsonPath("$.traceId").exists());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE drop_id=?", Long.class, drop)).isZero();
        // 거절도 HTTP 관측에 진입 경로 패턴으로 남는다 (#477).
        assertThat(registry.find("http.server.requests").tag("uri", "/api/drops/{dropId}/queue/entries")
                .tag("status", "503").timer()).isNotNull();

        limiter.release(held.remove(0));
        enter().andExpect(status().isCreated());
    }

    @Test
    @DisplayName("조회 허용치가 모두 차 있으면 503이고 heartbeat를 갱신하지 않으며, 진입은 영향이 없다")
    void statusAboveLimitIsRejected() throws Exception {
        enter().andExpect(status().isCreated());
        Timestamp before = jdbc.queryForObject("SELECT last_seen_at FROM drop_queue_entries WHERE drop_id=?",
                Timestamp.class, drop);
        exhaust(Kind.STATUS);
        me().andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("DROP_QUEUE_BUSY"));
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM drop_queue_entries WHERE drop_id=?",
                Timestamp.class, drop)).isEqualTo(before);
        enter().andExpect(status().isOk());

        limiter.release(held.remove(0));
        me().andExpect(status().isOk());
    }

    @Test
    @DisplayName("요청이 예외로 끝나도 허용치가 새지 않는다")
    void failedRequestsDoNotLeakPermits() throws Exception {
        int entryBefore = limiter.available(Kind.ENTRY);
        int statusBefore = limiter.available(Kind.STATUS);
        me().andExpect(status().isNotFound());
        mvc.perform(post("/api/drops/{dropId}/queue/entries", drop)
                .with(jwt().jwt(token -> token.subject(user.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertThat(limiter.available(Kind.ENTRY)).isEqualTo(entryBefore);
        assertThat(limiter.available(Kind.STATUS)).isEqualTo(statusBefore);
    }

    @Test
    @DisplayName("재고 점유 경로는 대기열 허용치가 모두 차 있어도 503이 아니다")
    void reservationIsNotLimited() throws Exception {
        exhaust(Kind.ENTRY);
        exhaust(Kind.STATUS);
        mvc.perform(post("/api/drops/{dropId}/reservations", drop)
                        .with(jwt().jwt(token -> token.subject(user.toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":" + variant + ",\"recipient\":\"홍길동\",\"phone\":\"01012345678\",\"address\":\"서울\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DROP_ADMISSION_REQUIRED"));
    }
}
