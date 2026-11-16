package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=true", "restless.drop.queue.admit-per-run=2"})
@Import({TestcontainersConfiguration.class, DropQueueIntegrationTest.TimeConfig.class})
class DropQueueIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService service;
    @Autowired DropQueueService queue;
    @Autowired DropQueueAdmissionJob job;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;
    private final Instant opens = Instant.parse("2026-11-20T03:00:00Z");
    private Long user;
    private Long drop;
    private Long variant;

    @BeforeEach
    void seed() {
        clock.now = opens;
        var email = UUID.randomUUID() + "@d2.test";
        var now = Timestamp.from(opens);
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'd2', 'USER', 'ACTIVE', ?, ?)
                """, email, now, now);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var venueName = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", venueName, now);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, venueName);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, email, now);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, email);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", opens, opens.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 39000L, 3)))).dropId();
        admin.publish(drop);
        variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop);
    }

    private CreateDropReservationRequest request() {
        return new CreateDropReservationRequest(variant, "회원", "010-1234-5678", "서울");
    }

    @Test
    void inv06SoldOutPreservesAdmissionAndReplayNeedsNoAdmission() {
        queue.enter(drop, user);
        job.runOnce();
        jdbc.update("UPDATE drop_inventories SET available_quantity=0, sold_quantity=3 WHERE variant_id=?", variant);
        assertCode(() -> service.create(drop, user, "sold-out", request()), ErrorCode.DROP_SOLD_OUT);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
        jdbc.update("UPDATE drop_inventories SET available_quantity=3, sold_quantity=0 WHERE variant_id=?", variant);
        var created = service.create(drop, user, "success", request());
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.CONSUMED);
        clock.now = opens.plusSeconds(400);
        assertThat(service.create(drop, user, "success", request()).body()).isEqualTo(created.body());
        assertThat(service.create(drop, user, "success", request()).replayed()).isTrue();
    }

    @Test
    void inv06ExpiryBoundaryAndFailedOrderPreserveAdmission() {
        queue.enter(drop, user);
        job.runOnce();
        var invalid = new CreateDropReservationRequest(variant, "회원", "010", " ");
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(drop, user, "retry", invalid));
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
        clock.now = opens.plusSeconds(300);
        assertCode(() -> service.create(drop, user, "expired", request()), ErrorCode.DROP_ADMISSION_REQUIRED);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.EXPIRED);
        assertThat(queue.enter(drop, user).existing()).isFalse();
    }

    @Test
    void concurrentEntryHasOneLiveRowAndOneEnteredMetric() throws Exception {
        var before = meters.counter("drop.queue.entered").count();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<DropQueueService.Entered>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                start.await();
                return queue.enter(drop, user);
            }));
            start.countDown();
            int created = 0;
            for (var task : tasks) if (!task.get(10, java.util.concurrent.TimeUnit.SECONDS).existing()) created++;
            assertThat(created).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Integer.class, drop, user)).isEqualTo(1);
        assertThat(meters.counter("drop.queue.entered").count()).isEqualTo(before + 1);
    }

    @Test
    void heartbeatBoundaryClosesBefore404AndReentryGetsNewSequence() {
        queue.enter(drop, user);
        long first = jdbc.queryForObject("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user);
        clock.now = opens.plusSeconds(30);
        assertThat(queue.me(drop, user)).isPresent();
        clock.now = opens.plusSeconds(61);
        assertThat(queue.me(drop, user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE id=?", String.class, first)).isEqualTo("CLOSED");
        assertThat(queue.enter(drop, user).existing()).isFalse();
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user)).isGreaterThan(first);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
