package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.acceptance.DropStock;
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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class DropQueueIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService service;
    @Autowired DropQueueService queue;
    @Autowired DropQueueAdmissionJob job;
    @Autowired DropQueueAdmissionTransactions batches;
    @Autowired DropQueueGateRepair gateRepair;
    @Autowired javax.sql.DataSource dataSource;
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
    void inv06SoldOutClosesAdmissionAndReplayNeedsNoAdmission() {
        queue.enter(drop, user, variant);
        job.runOnce();
        DropStock.setAvailable(jdbc, variant, 0);
        assertCode(() -> service.create(drop, user, "sold-out", request()), ErrorCode.DROP_SOLD_OUT);
        job.runOnce();
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.UNAVAILABLE);
        DropStock.setAvailable(jdbc, variant, 3);
        assertCode(() -> service.create(drop, user, "closed-grant", request()), ErrorCode.DROP_ADMISSION_REQUIRED);
        queue.enter(drop, user, variant);
        job.runOnce();
        var created = service.create(drop, user, "success", request());
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.CONSUMED);
        clock.now = opens.plusSeconds(400);
        assertThat(service.create(drop, user, "success", request()).body()).isEqualTo(created.body());
        assertThat(service.create(drop, user, "success", request()).replayed()).isTrue();
    }

    @Test
    void inv06ExpiryBoundaryAndFailedOrderPreserveAdmission() {
        queue.enter(drop, user, variant);
        job.runOnce();
        var invalid = new CreateDropReservationRequest(variant, "회원", "010", " ");
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(drop, user, "retry", invalid));
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
        clock.now = opens.plusSeconds(300);
        assertCode(() -> service.create(drop, user, "expired", request()), ErrorCode.DROP_ADMISSION_REQUIRED);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.EXPIRED);
        assertThat(queue.enter(drop, user, variant).existing()).isFalse();
    }

    @Test
    void concurrentEntryHasOneLiveRowAndOneEnteredMetric() throws Exception {
        var before = meters.counter("drop.queue.entered").count();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<DropQueueService.Entered>>();
            for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> {
                start.await();
                return queue.enter(drop, user, variant);
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
        queue.enter(drop, user, variant);
        long first = jdbc.queryForObject("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user);
        clock.now = opens.plusSeconds(30);
        assertThat(queue.me(drop, user)).isPresent();
        clock.now = opens.plusSeconds(61);
        assertThat(queue.me(drop, user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE id=?", String.class, first)).isEqualTo("CLOSED");
        assertThat(queue.enter(drop, user, variant).existing()).isFalse();
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user)).isGreaterThan(first);
    }

    @Test
    void busySkuGateDoesNotBlockOtherSkuOrTx1(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var missingBefore = meters.counter("drop.queue.gate.missing").count();
        queue.enter(drop, user, variant);
        job.runOnce(); // Give this user a grant before holding the gate.
        var second = admin.create(new CreateDropRequest(
                jdbc.queryForObject("SELECT concert_id FROM drops WHERE id=?", Long.class, drop),
                "other", "description", "url", opens, opens.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("B", "L", 100L, 1)))).dropId();
        admin.publish(second);
        var otherSku = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id=?", Long.class, second);
        queue.enter(second, user, otherSku);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT variant_id FROM drop_queue_gates WHERE variant_id=? FOR UPDATE")) {
                statement.setLong(1, variant);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                try {
                    executor.submit(job::runOnce).get(3, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(queue.me(second, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
                    // TX1 must not acquire the worker gate, even while it is held by another connection.
                    var hold = executor.submit(() -> service.create(drop, user, "gate-independent", request()))
                            .get(3, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(hold.replayed()).isFalse();
                } finally { connection.rollback(); }
            }
            assertThat(meters.counter("drop.queue.gate.missing").count()).isEqualTo(missingBefore);
            assertThat(output.getAll()).doesNotContain("Drop queue gate missing variantId=" + variant);
        }
    }

    @Test
    void closureRechecksAvailableInEachCommittedBatchAndTerminalPollingDoesNotHeartbeat() {
        // Many users are inserted in one statement to keep the fixture small and realistic.
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                SELECT CONCAT(UUID(), '@batch.test'), 'hash', 'batch', 'USER', 'ACTIVE', ?, ?
                FROM information_schema.columns LIMIT 105
                """, Timestamp.from(opens), Timestamp.from(opens));
        jdbc.update("""
                INSERT INTO drop_queue_entries (drop_id,user_id,variant_id,status,entered_at,last_seen_at)
                SELECT ?,id,?,'WAITING',?,? FROM users WHERE nickname='batch'
                """, drop, variant, Timestamp.from(opens), Timestamp.from(opens));
        DropStock.setAvailable(jdbc, variant, 0);
        assertThat(batches.runBatch(variant)).isEqualTo(DropQueueAdmissionTransactions.Result.MORE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='UNAVAILABLE'", Integer.class, variant)).isEqualTo(100);
        DropStock.setAvailable(jdbc, variant, 3);
        batches.runBatch(variant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='UNAVAILABLE'", Integer.class, variant)).isEqualTo(100);
        var closedUser = jdbc.queryForObject("SELECT user_id FROM drop_queue_entries WHERE variant_id=? AND status='UNAVAILABLE' ORDER BY id LIMIT 1", Long.class, variant);
        clock.now = opens.plusSeconds(10);
        assertThat(queue.me(drop, closedUser).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM drop_queue_entries WHERE variant_id=? AND user_id=?", Timestamp.class, variant, closedUser).toInstant()).isEqualTo(opens);
    }

    @Test
    void closureWaitsForConcurrentQueueReadInsteadOfLeavingLiveGrantBehind() throws Exception {
        queue.enter(drop, user, variant);
        DropStock.setAvailable(jdbc, variant, 0);
        try (var connection = dataSource.getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=? FOR UPDATE")) {
                statement.setLong(1, drop);
                statement.setLong(2, user);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            var result = executor.submit(() -> batches.runBatch(variant));
            try {
                assertThatThrownBy(() -> result.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { connection.rollback(); }
            result.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.UNAVAILABLE);
    }

    @Test
    void staleWaitingClosesEvenWhenExistingGrantsCoverAllAvailableStock() {
        assertCoveredWaitingCloses(opens.plusSeconds(31));
    }

    @Test
    void saleEndedWaitingClosesEvenWhenExistingGrantsCoverAllAvailableStock() {
        // Sale ends before the existing buyer's five-minute admission expires.
        jdbc.update("UPDATE drops SET closes_at=? WHERE id=?", Timestamp.from(opens.plusSeconds(10)), drop);
        assertCoveredWaitingCloses(opens.plusSeconds(11));
    }

    private void assertCoveredWaitingCloses(Instant later) {
        DropStock.setAvailable(jdbc, variant, 1);
        queue.enter(drop, user, variant);
        job.runOnce();
        jdbc.update("""
                INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)
                VALUES (?, 'hash', 'waiting', 'USER', 'ACTIVE', ?, ?)
                """, "wait-" + UUID.randomUUID() + "@test", Timestamp.from(opens), Timestamp.from(opens));
        var waitingUser = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        queue.enter(drop, waitingUser, variant);
        clock.now = later;
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE drop_id=? AND user_id=? ORDER BY id DESC LIMIT 1",
                String.class, drop, waitingUser)).isEqualTo("CLOSED");
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
    }

    @Test
    void missingGateIsRepairedAndAdmitsWithOneErrorAndUntaggedMetric(org.springframework.boot.test.system.CapturedOutput output) {
        queue.enter(drop, user, variant);
        jdbc.update("DELETE FROM drop_queue_gates WHERE variant_id=?", variant);
        var before = meters.counter("drop.queue.gate.missing").count();
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_gates WHERE variant_id=?", Integer.class, variant)).isEqualTo(1);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
        assertThat(meters.counter("drop.queue.gate.missing").count()).isEqualTo(before + 1);
        assertThat(meters.find("drop.queue.gate.missing").counter().getId().getTags()).isEmpty();
        var errors = output.getAll().lines().filter(line -> line.contains("ERROR") && line.contains("Drop queue gate missing variantId=" + variant)).count();
        assertThat(errors).isEqualTo(1);
    }

    @Test
    void concurrentMissingGateRepairCommitsOneRowAndMakesProgress() throws Exception {
        queue.enter(drop, user, variant);
        jdbc.update("DELETE FROM drop_queue_gates WHERE variant_id=?", variant);
        assertThat(batches.runBatch(variant)).isEqualTo(DropQueueAdmissionTransactions.Result.MISSING);
        assertThat(batches.runBatch(variant)).isEqualTo(DropQueueAdmissionTransactions.Result.MISSING);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                start.await();
                gateRepair.createIfMissing(variant);
                job.runOnce();
                return null;
            }));
            start.countDown();
            for (var task : tasks) task.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_gates WHERE variant_id=?", Integer.class, variant)).isEqualTo(1);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
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
