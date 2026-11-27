package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.domain.DropQueueStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.RepeatedTest;
import com.restless.drop.acceptance.DropStock;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired SnapshotSelects selects;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate transactions;
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
        // Rows left by earlier tests share this database. The job now expires them and would move global counters.
        jdbc.update("UPDATE drop_queue_entries SET status='CLOSED' WHERE status IN ('WAITING','ADMITTED') AND variant_id <> ?", variant);
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
        var expired = meters.counter("drop.queue.closed", "reason", "expired");
        var before = expired.count();
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(DropQueueStatus.EXPIRED);
        assertThat(expired.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE drop_id=? AND user_id=?",
                String.class, drop, user)).isEqualTo("ADMITTED");
        assertThat(queue.enter(drop, user, variant).existing()).isFalse();
        assertThat(expired.count()).isEqualTo(before + 1);
    }

    private String entryStatus() {
        return jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE drop_id=? AND user_id=? ORDER BY id DESC LIMIT 1",
                String.class, drop, user);
    }

    @Test
    void expiredAdmissionStaysExpiredWhenSoldOutAndIsCountedOnce() {
        queue.enter(drop, user, variant);
        job.runOnce();
        clock.now = opens.plusSeconds(300);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(DropQueueStatus.EXPIRED);
        var expired = meters.counter("drop.queue.closed", "reason", "expired");
        var unavailable = meters.counter("drop.queue.unavailable");
        var expiredBefore = expired.count();
        var unavailableBefore = unavailable.count();
        DropStock.setAvailable(jdbc, variant, 0);
        job.runOnce();
        assertThat(entryStatus()).isEqualTo("EXPIRED");
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(DropQueueStatus.EXPIRED);
        job.runOnce();
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(DropQueueStatus.EXPIRED);
        assertThat(expired.count()).isEqualTo(expiredBefore + 1);
        assertThat(unavailable.count()).isEqualTo(unavailableBefore);
    }

    @Test
    void soldOutClosureSplitsExpiredFromValidAdmissionsInOneBatch() {
        queue.enter(drop, user, variant);
        job.runOnce();
        clock.now = opens.plusSeconds(200);
        jdbc.update("""
                INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)
                VALUES (?, 'hash', 'later', 'USER', 'ACTIVE', ?, ?)
                """, "later-" + UUID.randomUUID() + "@test", Timestamp.from(opens), Timestamp.from(opens));
        var later = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        queue.enter(drop, later, variant);
        job.runOnce();
        clock.now = opens.plusSeconds(300);
        DropStock.setAvailable(jdbc, variant, 0);
        job.runOnce();
        assertThat(entryStatus()).isEqualTo("EXPIRED");
        assertThat(queue.me(drop, later).orElseThrow().status()).isEqualTo(DropQueueStatus.UNAVAILABLE);
    }

    @Test
    void expiredAdmissionClosesWithStockLeftAndLeavesLiveList() {
        queue.enter(drop, user, variant);
        job.runOnce();
        assertThat(jdbc.queryForList("SELECT DISTINCT variant_id FROM drop_queue_entries WHERE status IN ('WAITING','ADMITTED')", Long.class))
                .containsExactly(variant);
        clock.now = opens.plusSeconds(299);
        job.runOnce();
        assertThat(entryStatus()).isEqualTo("ADMITTED");
        clock.now = opens.plusSeconds(300);
        var expired = meters.counter("drop.queue.closed", "reason", "expired");
        var before = expired.count();
        job.runOnce();
        assertThat(entryStatus()).isEqualTo("EXPIRED");
        assertThat(expired.count()).isEqualTo(before + 1);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(DropQueueStatus.EXPIRED);
        assertThat(queue.enter(drop, user, variant).existing()).isFalse();
        assertThat(expired.count()).isEqualTo(before + 1);
        job.runOnce();
        assertThat(expired.count()).isEqualTo(before + 1);
    }

    @Test
    void expiredCleanupSkipsRowHeldByTx1AndNeverTouchesConsumedRow() throws Exception {
        queue.enter(drop, user, variant);
        job.runOnce();
        clock.now = opens.plusSeconds(300);
        try (var connection = dataSource.getConnection(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=? FOR UPDATE")) {
                statement.setLong(1, drop);
                statement.setLong(2, user);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            try {
                // The held row is skipped, the batch does not wait.
                assertThat(executor.submit(() -> batches.runBatch(variant)).get(3, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
                assertThat(entryStatus()).isEqualTo("ADMITTED");
                // The holder consumes the row before cleanup can see it.
                try (var statement = connection.prepareStatement("UPDATE drop_queue_entries SET status='CONSUMED', consumed_at=? WHERE drop_id=? AND user_id=?")) {
                    statement.setTimestamp(1, Timestamp.from(opens.plusSeconds(299)));
                    statement.setLong(2, drop);
                    statement.setLong(3, user);
                    statement.executeUpdate();
                }
            } finally { connection.commit(); }
        }
        var expired = meters.counter("drop.queue.closed", "reason", "expired");
        var before = expired.count();
        batches.runBatch(variant);
        assertThat(entryStatus()).isEqualTo("CONSUMED");
        DropStock.setAvailable(jdbc, variant, 0);
        batches.runBatch(variant);
        assertThat(entryStatus()).isEqualTo("CONSUMED");
        assertThat(expired.count()).isEqualTo(before);
    }

    @Test
    void pollAcrossExpiredCleanupKeepsAnsweringExpired() {
        queue.enter(drop, user, variant);
        job.runOnce();
        clock.now = opens.plusSeconds(301);
        var first = queue.me(drop, user).orElseThrow();
        job.runOnce();
        var second = queue.me(drop, user).orElseThrow();
        assertThat(first).isEqualTo(second);
        assertThat(second.status()).isEqualTo(DropQueueStatus.EXPIRED);
        assertThat(second.admittedUntil()).isNull();
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
    void heartbeatBoundaryReturns404BeforeCleanupAndReentryGetsNewSequence() {
        queue.enter(drop, user, variant);
        long first = jdbc.queryForObject("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user);
        clock.now = opens.plusSeconds(40);
        assertThat(queue.me(drop, user)).isPresent();
        clock.now = opens.plusSeconds(80).plusNanos(1000);
        assertThat(queue.me(drop, user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM drop_queue_entries WHERE id=?", String.class, first)).isEqualTo("WAITING");
        assertThat(queue.enter(drop, user, variant).existing()).isFalse();
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user)).isGreaterThan(first);
    }

    @Test
    void pollsWithinIntervalUseOneSelectAndNoCommit() {
        enterAs(user);
        clock.now = opens.plusSeconds(10);
        assertThat(queue.me(drop, user).orElseThrow().position()).isOne(); // warm snapshot
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            long commits = stats.getSuccessfulTransactionCount();
            long statements = stats.getPrepareStatementCount();
            for (int i = 0; i < 5; i++) {
                assertThat(queue.me(drop, user).orElseThrow().position()).isOne();
            }
            assertThat(stats.getPrepareStatementCount() - statements).isEqualTo(5);
            assertThat(stats.getSuccessfulTransactionCount()).isEqualTo(commits);
            assertThat(lastSeen()).isEqualTo(opens);

            clock.now = clock.now.plusNanos(1000);
            statements = stats.getPrepareStatementCount();
            for (int i = 0; i < 5; i++) {
                assertThat(queue.me(drop, user).orElseThrow().position()).isOne();
            }
            assertThat(stats.getPrepareStatementCount() - statements).isEqualTo(6);
            assertThat(stats.getSuccessfulTransactionCount() - commits).isOne();
            assertThat(lastSeen()).isEqualTo(clock.now);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    @RepeatedTest(4)
    void inv17CloseCommitsBetweenPollReadAndHeartbeat() throws Exception {
        enterAs(user);
        clock.now = opens.plusSeconds(11);
        DropStock.setAvailable(jdbc, variant, 0);
        raceHeartbeat(() -> {
            assertThat(batches.runBatch(variant)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status IN ('WAITING','ADMITTED')
                    """, Long.class, variant)).isZero();
        }, DropQueueStatus.UNAVAILABLE, opens);
    }

    @Test
    void tx1ConsumesGrantBetweenPollReadAndHeartbeat() throws Exception {
        enterAs(user);
        clock.now = opens.plusSeconds(11);
        raceHeartbeat(() -> {
            batches.runBatch(variant);
            assertThat(service.create(drop, user, "overlap", request()).replayed()).isFalse();
        }, DropQueueStatus.CONSUMED, opens);
    }

    @Test
    void concurrentPollCannotOverwriteNewerHeartbeat() throws Exception {
        enterAs(user);
        clock.now = opens.plusSeconds(11);
        var later = opens.plusSeconds(12);
        raceHeartbeat(() -> {
            clock.now = later;
            assertThat(queue.me(drop, user).orElseThrow().position()).isOne();
        }, DropQueueStatus.WAITING, later);
    }

    private void raceHeartbeat(Runnable transition, DropQueueStatus expected, Instant expectedLastSeen) throws Exception {
        var beforeUpdate = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var poll = executor.submit(() -> {
                selects.beforeHeartbeat.set(() -> {
                    beforeUpdate.countDown();
                    try {
                        assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                });
                try {
                    return queue.me(drop, user).orElseThrow();
                } finally {
                    selects.beforeHeartbeat.remove();
                }
            });
            try {
                assertThat(beforeUpdate.await(10, TimeUnit.SECONDS)).isTrue();
                transition.run();
            } finally {
                resume.countDown();
            }
            Long position = expected == DropQueueStatus.WAITING ? 1L : null;
            assertThat(poll.get(10, TimeUnit.SECONDS)).isEqualTo(new DropQueueService.View(expected, variant, position, null, position == null ? null : 1000L));
        }
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(expected);
        assertThat(lastSeen()).isEqualTo(expectedLastSeen);
    }

    private Instant lastSeen() {
        return jdbc.queryForObject("SELECT last_seen_at FROM drop_queue_entries WHERE drop_id=? AND user_id=?",
                Timestamp.class, drop, user).toInstant();
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
            assertThat(output.getAll()).doesNotContain("event=drop.queue.gate.repair_started variantId=" + variant,
                    "event=drop.queue.gate.repair_unresolved variantId=" + variant);
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
        assertCoveredWaitingCloses(opens.plusSeconds(41));
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
    void missingGateIsRepairedAndAdmitsWithOneWarningAndUntaggedMetric(org.springframework.boot.test.system.CapturedOutput output) {
        queue.enter(drop, user, variant);
        jdbc.update("DELETE FROM drop_queue_gates WHERE variant_id=?", variant);
        var before = meters.counter("drop.queue.gate.missing").count();
        job.runOnce();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_gates WHERE variant_id=?", Integer.class, variant)).isEqualTo(1);
        assertThat(queue.me(drop, user).orElseThrow().status()).isEqualTo(com.restless.drop.domain.DropQueueStatus.ADMITTED);
        assertThat(meters.counter("drop.queue.gate.missing").count()).isEqualTo(before + 1);
        assertThat(meters.find("drop.queue.gate.missing").counter().getId().getTags()).isEmpty();
        var repairEvents = output.getAll().lines()
                .filter(line -> line.contains("event=drop.queue.gate.repair_") && line.endsWith("variantId=" + variant))
                .toList();
        assertThat(repairEvents).singleElement().asString()
                .contains("WARN", "event=drop.queue.gate.repair_started");
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

    // #377: position counts fresh WAITING rows of the same SKU up to and including the caller's id.
    @Test
    void positionCountsOnlyFreshWaitingAheadInSameSku() {
        var cutoff = opens.plusSeconds(1);
        clock.now = cutoff.minusNanos(1_000);
        enterAs(newUser()); // stale by 1 microsecond: excluded
        clock.now = cutoff;
        enterAs(newUser()); // last_seen_at == cutoff: counted
        clock.now = cutoff.plusSeconds(1);
        var admitted = newUser();
        enterAs(admitted);
        jdbc.update("UPDATE drop_queue_entries SET status='ADMITTED', admitted_at=?, admitted_until=? WHERE drop_id=? AND user_id=?",
                Timestamp.from(clock.now), Timestamp.from(clock.now.plusSeconds(300)), drop, admitted); // not WAITING: excluded
        var other = admin.create(new CreateDropRequest(
                jdbc.queryForObject("SELECT concert_id FROM drops WHERE id=?", Long.class, drop),
                "other", "description", "url", opens, opens.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("B", "L", 100L, 1)))).dropId();
        admin.publish(other);
        var otherSku = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id=?", Long.class, other);
        queue.enter(other, newUser(), otherSku); // other SKU: excluded
        clock.now = cutoff.plusSeconds(2);
        enterAs(newUser()); // fresh, ahead: counted
        clock.now = cutoff.plusSeconds(3);
        var me = enterAs(user); // self: counted
        clock.now = cutoff.plusSeconds(4);
        enterAs(newUser()); // behind: excluded
        var myId = jdbc.queryForObject("SELECT id FROM drop_queue_entries WHERE drop_id=? AND user_id=?", Long.class, drop, user);

        var reference = jdbc.queryForObject("""
                SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='WAITING'
                  AND id <= ? AND last_seen_at >= ?""", Long.class, variant, myId, Timestamp.from(cutoff));
        assertThat(reference).isEqualTo(3);
        assertThat(me.body().position()).as("enter response").isEqualTo(4);
        clock.now = cutoff.plusSeconds(40); // queue/me cutoff is now - 40s == cutoff
        assertThat(queue.me(drop, user).orElseThrow().position()).isEqualTo(reference);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='drop_queue_entries' AND index_name='ix_drop_queue_sku_position'
                ORDER BY seq_in_index""", String.class))
                .containsExactly("variant_id", "status", "id", "last_seen_at");
    }

    // INV-17 boundary: the tail COUNT and the waiting gauge use the 40s heartbeat timeout, not 30s.
    // The snapshot is cached first, so rows added afterwards are only counted by the tail query.
    @Test
    void tailCountUsesFortySecondHeartbeatBoundary() {
        enterAs(newUser());
        clock.now = opens.plusSeconds(35);
        enterAs(newUser()); // fills the snapshot for this epoch second
        queue.me(drop, user).ifPresent(ignored -> { });
        var cutoff = clock.now.minusSeconds(40);
        insertWaiting(cutoff.minusNanos(1_000)); // 40s plus 1 microsecond: excluded
        insertWaiting(cutoff); // exactly 40s: counted
        insertWaiting(clock.now.minusSeconds(35)); // between 30s and 40s: counted
        insertWaiting(clock.now.minusSeconds(31)); // between 30s and 40s: counted
        var me = enterAs(user);
        // snapshot ids: first user (35s old, counted via cutoff) and second user, then 3 counted tail rows and self
        long expected = jdbc.queryForObject("""
                SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='WAITING'
                  AND id <= (SELECT MAX(id) FROM drop_queue_entries WHERE drop_id=? AND user_id=?)
                  AND last_seen_at >= ?""", Long.class, variant, drop, user, Timestamp.from(cutoff));
        assertThat(expected).isEqualTo(6);
        assertThat(me.body().position()).isEqualTo(expected);
        assertThat(queue.me(drop, user).orElseThrow().position()).isEqualTo(expected);
    }

    @Test
    void waitingGaugeUsesFortySecondHeartbeatBoundary() {
        clock.now = opens.plusSeconds(100);
        double before = meters.get("drop.queue.waiting").gauge().value();
        insertWaiting(clock.now.minusSeconds(40).minusNanos(1_000)); // excluded
        insertWaiting(clock.now.minusSeconds(40)); // exactly 40s: counted
        insertWaiting(clock.now.minusSeconds(35)); // between 30s and 40s: counted
        assertThat(meters.get("drop.queue.waiting").gauge().value()).isEqualTo(before + 2);
    }

    private void insertWaiting(Instant lastSeen) {
        jdbc.update("INSERT INTO drop_queue_entries (drop_id,user_id,variant_id,status,entered_at,last_seen_at) VALUES (?,?,?,'WAITING',?,?)",
                drop, newUser(), variant, Timestamp.from(lastSeen), Timestamp.from(lastSeen));
    }

    @Test
    void sameSecondPollsReuseOneSnapshotUntilHeartbeatCutoffMoves() {
        enterAs(newUser());
        clock.now = opens.plusSeconds(1);
        enterAs(user);
        clock.now = opens.plusSeconds(40);
        int before = selects.count.get();
        assertThat(queue.me(drop, user).orElseThrow().position()).isEqualTo(2);
        clock.now = clock.now.plusMillis(999);
        assertThat(queue.me(drop, user).orElseThrow().position()).isEqualTo(2);
        assertThat(selects.count.get() - before).isOne();
        clock.now = opens.plusSeconds(41);
        assertThat(queue.me(drop, user).orElseThrow().position()).isOne();
        assertThat(selects.count.get() - before).isEqualTo(2);
    }

    @Test
    void rolledBackFirstEntryDoesNotOccupyASharedPosition() {
        transactions.executeWithoutResult(transaction -> {
            assertThat(enterAs(user).body().position()).isOne();
            transaction.setRollbackOnly();
        });
        assertThat(queue.me(drop, user)).isEmpty();
        assertThat(enterAs(newUser()).body().position()).isOne();
    }

    @Test
    void concurrentPollsAndEntersShareOneSkuRead() throws Exception {
        var waiting = new ArrayList<Long>();
        var joining = new ArrayList<Long>();
        for (int i = 0; i < 10; i++) {
            waiting.add(newUser());
            enterAs(waiting.get(i));
            joining.add(newUser());
        }
        clock.now = opens.plusSeconds(1);
        int before = selects.count.get();
        var ready = new CountDownLatch(210);
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Future<Long>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 210; i++) {
                boolean enter = i >= 200;
                long member = (enter ? joining : waiting).get(i % 10);
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return enter ? enterAs(member).body().position() : queue.me(drop, member).orElseThrow().position();
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (var task : tasks) assertThat(task.get(30, TimeUnit.SECONDS)).isBetween(1L, 20L);
        }
        assertThat(selects.count.get() - before).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='WAITING'",
                Long.class, variant)).isEqualTo(20);
    }

    static class SnapshotSelects implements StatementInspector {
        final AtomicInteger count = new AtomicInteger();
        final ThreadLocal<Runnable> beforeHeartbeat = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            if (sql.contains("SELECT id FROM drop_queue_entries USE INDEX")) count.incrementAndGet();
            if (sql.startsWith("UPDATE drop_queue_entries SET last_seen_at") && beforeHeartbeat.get() != null)
                beforeHeartbeat.get().run();
            return sql;
        }
    }

    private DropQueueService.Entered enterAs(Long member) {
        return queue.enter(drop, member, variant);
    }

    private Long newUser() {
        var email = UUID.randomUUID() + "@position.test";
        var now = Timestamp.from(opens);
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'position', 'USER', 'ACTIVE', ?, ?)
                """, email, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean SnapshotSelects snapshotSelects() { return new SnapshotSelects(); }
        @Bean HibernatePropertiesCustomizer countSelects(SnapshotSelects selects) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", selects);
        }
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
