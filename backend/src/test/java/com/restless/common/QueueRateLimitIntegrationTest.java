package com.restless.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.common.ratelimit.RateLimitProperties;
import com.restless.drop.api.DropQueueController;
import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.dto.CreateDropRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"restless.ratelimit.enabled=true", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.required=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, QueueRateLimitIntegrationTest.TimeConfig.class})
class QueueRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired RateLimitProperties limits;
    @Autowired MutableClock clock;
    private Long user;
    private Long drop;

    @BeforeEach
    void createWaitingEntryThroughRealController() throws Exception {
        clock.advanceMillis(10_000);
        Instant now = clock.instant();
        Timestamp timestamp = Timestamp.from(now);
        String unique = UUID.randomUUID().toString();
        String email = unique + "@ratelimit.test";
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'ratelimit', 'USER', 'ACTIVE', ?, ?)
                """, email, timestamp, timestamp);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                unique, timestamp);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name=?", Long.class, unique);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, unique, timestamp);
        Long concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title=?", Long.class, unique);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now, now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 1000L, 3)))).dropId();
        admin.publish(drop);
        Long variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id=?", Long.class, drop);
        mvc.perform(post("/api/drops/{dropId}/queue/entries", drop)
                        .with(jwt().jwt(token -> token.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":" + variant + "}"))
                .andExpect(handler().handlerType(DropQueueController.class)).andExpect(status().isCreated());
        assertThat(lastSeen()).isEqualTo(now);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void rejectedStatusRequestDoesNotUpdateHeartbeatInDatabase(String method) throws Exception {
        Instant enteredAt = clock.instant();
        clock.advanceMillis(100);
        Instant acceptedAt = clock.instant();
        for (int i = 0; i < limits.queueStatus().limit(); i++) {
            mvc.perform(get("/api/drops/{dropId}/queue/me", drop)
                            .with(jwt().jwt(token -> token.subject(user.toString()))))
                    .andExpect(handler().handlerType(DropQueueController.class)).andExpect(status().isOk());
        }
        assertThat(lastSeen()).isEqualTo(acceptedAt).isAfter(enteredAt);

        clock.advanceMillis(200);
        mvc.perform(request(HttpMethod.valueOf(method), "/api/drops/{dropId}/queue/me", drop)
                        .with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(handler().handlerType(DropQueueController.class))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        assertThat(lastSeen()).isEqualTo(acceptedAt);
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE drop_id=? AND user_id=?",
                String.class, drop, user)).isEqualTo("WAITING");

        clock.advanceMillis(limits.queueStatus().window().toMillis());
        mvc.perform(get("/api/drops/{dropId}/queue/me", drop)
                        .with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk());
        assertThat(lastSeen()).isEqualTo(clock.instant()).isAfter(acceptedAt);
    }

    private Instant lastSeen() {
        return jdbc.queryForObject("SELECT last_seen_at FROM drop_queue_entries WHERE drop_id=? AND user_id=?",
                Timestamp.class, drop, user).toInstant();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary MutableClock rateLimitClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T03:00:00Z");
        void advanceMillis(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
