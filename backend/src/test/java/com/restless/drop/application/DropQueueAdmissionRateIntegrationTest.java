package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.application.dto.CreateDropRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * #551: several admission job threads (standing in for several servers) share one per-SKU window per second
 * through the gate row. The drop rate is split across the drop's SKUs, and the cap never breaks INV-16
 * (unused valid ADMITTED <= available).
 */
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=true", "restless.drop.queue.admit-per-run=100",
        "restless.drop.queue.admit-rate-per-second=7", "restless.drop.queue.scheduler.enabled=false"})
@Import({TestcontainersConfiguration.class, DropQueueIntegrationTest.TimeConfig.class})
class DropQueueAdmissionRateIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropQueueAdmissionJob job;
    @Autowired DropQueueAdmissionTransactions batches;
    @Autowired DropQueueIntegrationTest.MutableClock clock;
    private final Instant opens = Instant.parse("2026-11-20T03:00:00Z");
    private List<Long> variants;

    @BeforeEach
    void reset() {
        clock.now = opens;
    }

    @Test
    void concurrentJobsShareOneCapPerSecondAndNeverPassAvailableStock() throws Exception {
        seed(1, 20, 40);
        var variant = variants.getFirst();
        clock.now = opens.plusMillis(100);
        runConcurrently(8, 5);
        assertThat(admitted(variant)).as("first second, one SKU gets the whole drop rate").isEqualTo(7);
        assertThat(window(variant)).containsExactly(opens.getEpochSecond(), 7L);

        clock.now = opens.plusMillis(900);
        runConcurrently(8, 5);
        assertThat(admitted(variant)).as("same second, window full").isEqualTo(7);

        clock.now = opens.plusSeconds(1).plusMillis(50);
        runConcurrently(8, 5);
        assertThat(admitted(variant)).as("second window").isEqualTo(14);
        assertThat(window(variant)).containsExactly(opens.getEpochSecond() + 1, 7L);

        clock.now = opens.plusSeconds(2);
        runConcurrently(8, 5);
        // INV-16: stock is 20, so the third window may only grant 6 even though the cap allows 7.
        assertThat(admitted(variant)).as("third window bounded by stock").isEqualTo(20);
        assertThat(admitted(variant)).isLessThanOrEqualTo(available(variant));
        assertThat(window(variant)).containsExactly(opens.getEpochSecond() + 2, 6L);

        clock.now = opens.plusSeconds(3);
        runConcurrently(8, 5);
        assertThat(admitted(variant)).as("no grant beyond available stock").isEqualTo(20);
    }

    @Test
    void threeSkuDropSplitsTheRateAndEachSkuKeepsItsOwnWindow() throws Exception {
        seed(3, 20, 20);
        clock.now = opens.plusMillis(100);
        runConcurrently(8, 5);
        // ceil(7 / 3) = 3 per SKU per second.
        for (var variant : variants) {
            assertThat(admitted(variant)).as("first second, variant %d", variant).isEqualTo(3);
            assertThat(window(variant)).containsExactly(opens.getEpochSecond(), 3L);
        }
        clock.now = opens.plusSeconds(1);
        runConcurrently(8, 5);
        for (var variant : variants) assertThat(admitted(variant)).as("second window").isEqualTo(6);
    }

    @Test
    void clockBehindTheStoredWindowCannotResetIt() throws Exception {
        seed(1, 20, 40);
        var variant = variants.getFirst();
        clock.now = opens.plusSeconds(5);
        runConcurrently(4, 3);
        assertThat(admitted(variant)).isEqualTo(7);
        clock.now = opens.plusSeconds(4); // a server whose clock lags one second
        runConcurrently(4, 3);
        assertThat(admitted(variant)).isEqualTo(7);
        assertThat(window(variant)).containsExactly(opens.getEpochSecond() + 5, 7L);
    }

    private void seed(int skus, int stockPerSku, int waitingPerSku) {
        var seller = newUser();
        var now = Timestamp.from(opens);
        var venueName = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", venueName, now);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, venueName);
        var title = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, seller, title, now);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, title);
        var skuRequests = IntStream.range(0, skus)
                .mapToObj(i -> new CreateDropRequest.Variant("SKU" + i, "M" + i, 39000L, stockPerSku)).toList();
        var drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", opens, opens.plusSeconds(3600),
                skuRequests)).dropId();
        admin.publish(drop);
        variants = jdbc.queryForList("SELECT id FROM drop_variants WHERE drop_id = ? ORDER BY id", Long.class, drop);
        // Rows left by earlier tests share this database; the job must only see this drop's SKUs.
        jdbc.update("UPDATE drop_queue_entries SET status='CLOSED' WHERE status IN ('WAITING','ADMITTED') AND drop_id <> ?", drop);
        for (var variant : variants) {
            for (int i = 0; i < waitingPerSku; i++) {
                jdbc.update("INSERT INTO drop_queue_entries (drop_id,user_id,variant_id,status,entered_at,last_seen_at) VALUES (?,?,?,'WAITING',?,?)",
                        drop, newUser(), variant, now, now);
            }
        }
    }

    private void runConcurrently(int threads, int runsEach) throws Exception {
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                final boolean viaJob = t % 2 == 0;
                final int offset = t;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < runsEach; i++) {
                        if (viaJob) job.runOnce();
                        else batches.runBatch(variants.get((offset + i) % variants.size()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
    }

    private long admitted(Long variant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=? AND status='ADMITTED'",
                Long.class, variant);
    }

    private long available(Long variant) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(available_quantity),0) FROM drop_inventory_buckets WHERE variant_id=?",
                Long.class, variant);
    }

    private List<Long> window(Long variant) {
        return jdbc.queryForObject("SELECT rate_window_second, rate_window_admits FROM drop_queue_gates WHERE variant_id=?",
                (rs, i) -> List.of(rs.getLong(1), rs.getLong(2)), variant);
    }

    private Long newUser() {
        var email = UUID.randomUUID() + "@rate.test";
        var now = Timestamp.from(opens);
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'rate', 'USER', 'ACTIVE', ?, ?)
                """, email, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
