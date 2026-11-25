package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.DropQueueAdmissionJob;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import com.restless.drop.application.dto.CreateDropRequest;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 사용자별 요청 횟수 제한(429)이 동시 처리 상한(503)보다 먼저 판정되는 순서와 HEAD 처리를 고정한다 (#390). */
@SpringBootTest(properties = {"restless.ratelimit.enabled=true",
        // 실제 시계로 도는 테스트가 창 경계에 걸리지 않도록 창을 길게 둔다. Retry-After는 설정 검증을 맞춘다.
        "restless.ratelimit.queue-entry.window=1m", "restless.ratelimit.queue-status.window=1m",
        "restless.drop.queue.concurrency.entry-retry-after-seconds=12",
        "restless.drop.queue.concurrency.status-retry-after-seconds=12", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.required=true",
        "restless.drop.queue.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("요청 횟수 제한이 동시 처리 상한보다 먼저 돈다 (#390)")
class DropQueueLimitOrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropQueueAdmissionJob admission;
    @Autowired DropQueueConcurrencyLimiter limiter;
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

    private ResultActions me(HttpMethod method) throws Exception {
        return mvc.perform(request(method, "/api/drops/{dropId}/queue/me", drop)
                .with(jwt().jwt(token -> token.subject(user.toString()))));
    }

    @Test
    @DisplayName("사용자 한도를 넘긴 조회는 허용치가 가득 차도 503이 아니라 429다")
    void statusRateLimitIsCheckedBeforeConcurrencyLimit() throws Exception {
        enter().andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) me(HttpMethod.GET).andExpect(status().isOk());
        exhaust(Kind.STATUS);
        me(HttpMethod.GET).andExpect(status().isTooManyRequests());
        // 한도를 넘긴 요청은 허용치도 건드리지 않는다.
        assertThat(limiter.available(Kind.STATUS)).isZero();
        limiter.release(held.remove(0));
        assertThat(limiter.available(Kind.STATUS)).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자 한도를 넘긴 진입은 허용치가 가득 차도 503이 아니라 429다")
    void entryRateLimitIsCheckedBeforeConcurrencyLimit() throws Exception {
        enter().andExpect(status().isCreated());
        for (int i = 0; i < 4; i++) enter().andExpect(status().isOk());
        exhaust(Kind.ENTRY);
        enter().andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("한도 안의 사용자는 허용치가 가득 차면 503이다")
    void withinRateLimitGetsBusy() throws Exception {
        exhaust(Kind.STATUS);
        me(HttpMethod.GET).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("HEAD 조회도 GET과 같은 사용자 한도를 쓰고 같은 동시 처리 상한을 받는다")
    void headSharesRateLimitAndConcurrencyLimit() throws Exception {
        enter().andExpect(status().isCreated());
        me(HttpMethod.GET).andExpect(status().isOk());
        me(HttpMethod.HEAD).andExpect(status().isOk());
        me(HttpMethod.GET).andExpect(status().isOk());
        me(HttpMethod.HEAD).andExpect(status().isOk());
        me(HttpMethod.GET).andExpect(status().isOk());
        me(HttpMethod.HEAD).andExpect(status().isTooManyRequests());

        exhaust(Kind.STATUS);
        // 다른 사용자는 한도가 새로 시작하므로 HEAD가 503을 받는다.
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at) "
                + "VALUES (?, 'hash', 'busy2', 'USER', 'ACTIVE', NOW(), NOW())", UUID.randomUUID() + "@busy.test");
        Long other = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        mvc.perform(request(HttpMethod.HEAD, "/api/drops/{dropId}/queue/me", drop)
                .with(jwt().jwt(token -> token.subject(other.toString())))).andExpect(status().isServiceUnavailable());
    }
}
