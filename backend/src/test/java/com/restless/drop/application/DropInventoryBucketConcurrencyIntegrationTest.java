package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.acceptance.DropStock;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * ADR-0005 TX1 bucket selection under concurrency (design #327 r3 section 8). K is pinned to 4 so the
 * K = 1 regression run of the other drop tests does not change these scenarios.
 */
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false", "restless.drop.expiry.enabled=false",
        "restless.drop.inventory.bucket-count=4"})
@Import(TestcontainersConfiguration.class)
class DropInventoryBucketConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService service;
    @Autowired DropReservationLifecycleService lifecycle;
    @Autowired MeterRegistry meters;

    record Sku(Long drop, Long variant) { }

    @Test
    void creationSplitsEachSkuIntoMinOfKAndTotalBucketsWithFrontRemainder() {
        Long drop = newDrop(10, 3, 1);
        var variants = jdbc.queryForList("SELECT id FROM drop_variants WHERE drop_id = ? ORDER BY id", Long.class, drop);
        assertThat(DropStock.availableByBucket(jdbc, variants.get(0))).containsExactly(3, 3, 2, 2);
        assertThat(DropStock.availableByBucket(jdbc, variants.get(1))).containsExactly(1, 1, 1);
        assertThat(DropStock.availableByBucket(jdbc, variants.get(2))).containsExactly(1);
        assertThat(variants.stream().map(v -> DropStock.bucketCount(jdbc, v)).toList()).containsExactly(4, 3, 1);
        for (Long v : variants) {
            assertThat(jdbc.queryForObject("SELECT total_quantity FROM drop_inventories WHERE variant_id = ?", Integer.class, v))
                    .isEqualTo(DropStock.totals(jdbc, v).get("total"));
            assertThat(DropStock.totals(jdbc, v)).containsEntry("reserved", 0).containsEntry("sold", 0);
        }
    }

    @Test
    void inv01AndInv02TenUnitsAcrossFourBucketsGoToExactlyTenOfFifty() throws Exception {
        var sku = newSku(10);
        var outcomes = holdConcurrently(sku, newUsers(50));
        assertThat(outcomes.get("ok")).isEqualTo(10);
        assertThat(outcomes.get(ErrorCode.DROP_SOLD_OUT.name())).isEqualTo(40);
        assertOnly(outcomes, "ok", ErrorCode.DROP_SOLD_OUT.name());
        assertThat(DropStock.totals(jdbc, sku.variant())).containsEntry("available", 0).containsEntry("reserved", 10);
        assertBucketsConsistent(sku.variant());
    }

    @Test
    void requestsEqualToStockAllSucceedWithoutFalseSoldOut() throws Exception {
        // #327 r2 S5: without releases, a request must never be told sold out while another bucket has stock.
        for (int round = 0; round < 3; round++) {
            var sku = newSku(100);
            assertThat(DropStock.availableByBucket(jdbc, sku.variant())).containsExactly(25, 25, 25, 25);
            var outcomes = holdConcurrently(sku, newUsers(100));
            assertThat(outcomes.get("ok")).as("round %d", round).isEqualTo(100);
            assertOnly(outcomes, "ok");
            assertThat(DropStock.totals(jdbc, sku.variant())).containsEntry("available", 0).containsEntry("reserved", 100);
            assertBucketsConsistent(sku.variant());
        }
    }

    @Test
    void noDeadlockWhenTwoSingleUnitBucketsAreContended() throws Exception {
        // #327 r1 deadlock.out condition: two buckets with one unit each and many TX1.
        for (int round = 0; round < 3; round++) {
            var sku = newSku(2);
            assertThat(DropStock.availableByBucket(jdbc, sku.variant())).containsExactly(1, 1);
            var outcomes = holdConcurrently(sku, newUsers(20));
            assertThat(outcomes.get("ok")).isEqualTo(2);
            assertThat(outcomes.get(ErrorCode.DROP_SOLD_OUT.name())).isEqualTo(18);
            assertOnly(outcomes, "ok", ErrorCode.DROP_SOLD_OUT.name());
            assertBucketsConsistent(sku.variant());
        }
    }

    @Test
    void noDeadlockWhileAnotherTransactionHoldsEveryStockBucket() throws Exception {
        // #327 r2 S3 shape: every stock bucket is locked, TX1s wait (step 9), the holder then empties one bucket.
        var sku = newSku(2);
        var users = newUsers(20);
        var waited = meters.counter("drop.inventory.hold.path", "path", "waited").count();
        Map<String, Integer> outcomes;
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement(DropStock.LOCK_ALL_BUCKETS)) {
                lock.setLong(1, sku.variant());
                lock.executeQuery().close();
            }
            var pending = Executors.newSingleThreadExecutor().submit(() -> holdConcurrently(sku, users));
            awaitTx1AtBucketStep(sku.drop());
            try (var take = holder.prepareStatement("""
                    UPDATE drop_inventory_buckets SET available_quantity = available_quantity - 1,
                        sold_quantity = sold_quantity + 1 WHERE variant_id = ? AND bucket_no = 0""")) {
                take.setLong(1, sku.variant());
                assertThat(take.executeUpdate()).isEqualTo(1);
            }
            holder.commit();
            outcomes = pending.get(60, TimeUnit.SECONDS);
        }
        assertThat(outcomes.get("ok")).isEqualTo(1);
        assertThat(outcomes.get(ErrorCode.DROP_SOLD_OUT.name())).isEqualTo(19);
        assertOnly(outcomes, "ok", ErrorCode.DROP_SOLD_OUT.name());
        // Successes equal the committed stock decrease that is not the holder's.
        assertThat(DropStock.buckets(jdbc, sku.variant())).allSatisfy(b -> assertThat(b.get("available")).isZero());
        assertThat(DropStock.totals(jdbc, sku.variant())).containsEntry("reserved", 1).containsEntry("sold", 1);
        assertThat(meters.counter("drop.inventory.hold.path", "path", "waited").count()).isGreaterThan(waited);
        assertBucketsConsistent(sku.variant());
    }

    @Test
    void lastUnitsInOneBucketGoToExactlyTwoAndEveryPathIsCounted() throws Exception {
        var sku = newSku(8);
        DropStock.setAvailableByBucket(jdbc, sku.variant(), 0, 0, 0, 2);
        double picked = path("picked"), waited = path("waited"), soldOut = path("sold_out");
        var outcomes = holdConcurrently(sku, newUsers(20));
        assertThat(outcomes.get("ok")).isEqualTo(2);
        assertThat(outcomes.get(ErrorCode.DROP_SOLD_OUT.name())).isEqualTo(18);
        assertOnly(outcomes, "ok", ErrorCode.DROP_SOLD_OUT.name());
        assertThat(path("sold_out") - soldOut).isEqualTo(18);
        assertThat((path("picked") - picked) + (path("waited") - waited)).isEqualTo(2);
        assertThat(DropStock.availableByBucket(jdbc, sku.variant())).containsExactly(0, 0, 0, 0);
        assertThat(jdbc.queryForList("SELECT DISTINCT bucket_no FROM drop_reservations WHERE variant_id = ? AND status = 'RESERVED'",
                Integer.class, sku.variant())).containsExactly(3);
        assertBucketsConsistent(sku.variant());
    }

    @Test
    void releasesAndRolledBackHoldersMixedWithHoldsNeverDeadlockOrBreakBuckets() throws Exception {
        // #327 probe-r3 REL and RB: concurrent cancels (TX4-c) and lock holders that roll back, beside TX1s.
        var sku = newSku(40);
        var early = newUsers(12);
        var reservations = new ArrayList<Long>();
        for (Long user : early) reservations.add(hold(sku, user).body().reservationId());
        var buyers = newUsers(40);
        var outcomes = new ConcurrentHashMap<String, Integer>();
        var rollbacks = new AtomicInteger();
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Void>>();
        for (Long user : buyers) tasks.add(() -> { outcomes.merge(attempt(sku, user), 1, Integer::sum); return null; });
        for (int i = 0; i < early.size(); i++) {
            Long user = early.get(i), reservation = reservations.get(i);
            tasks.add(() -> {
                try {
                    lifecycle.cancel(sku.drop(), user, reservation);
                    outcomes.merge("cancelled", 1, Integer::sum);
                } catch (Exception e) {
                    outcomes.merge("cancel:" + e.getClass().getSimpleName() + ":" + e.getMessage(), 1, Integer::sum);
                }
                return null;
            });
        }
        for (int t = 0; t < 3; t++) {
            tasks.add(() -> {
                for (int i = 0; i < 5; i++) {
                    try (var holder = dataSource.getConnection()) {
                        holder.setAutoCommit(false);
                        holder.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                        try (var take = holder.prepareStatement("""
                                UPDATE drop_inventory_buckets SET available_quantity = available_quantity - 1,
                                    reserved_quantity = reserved_quantity + 1
                                WHERE variant_id = ? AND bucket_no = ? AND available_quantity > 0""")) {
                            take.setLong(1, sku.variant());
                            take.setInt(2, ThreadLocalRandom.current().nextInt(4));
                            take.executeUpdate();
                        }
                        Thread.sleep(5);
                        holder.rollback();
                        rollbacks.incrementAndGet();
                    } catch (Exception e) {
                        outcomes.merge("holder:" + e.getClass().getSimpleName() + ":" + e.getMessage(), 1, Integer::sum);
                    }
                }
                return null;
            });
        }
        runAll(tasks, start);
        assertThat(rollbacks.get()).isEqualTo(15);
        assertThat(outcomes.get("cancelled")).isEqualTo(12);
        int ok = outcomes.getOrDefault("ok", 0);
        assertThat(ok + outcomes.getOrDefault(ErrorCode.DROP_SOLD_OUT.name(), 0)).isEqualTo(40);
        assertOnly(outcomes, "ok", ErrorCode.DROP_SOLD_OUT.name(), "cancelled");
        assertThat(DropStock.totals(jdbc, sku.variant())).containsEntry("total", 40).containsEntry("reserved", ok)
                .containsEntry("available", 40 - ok).containsEntry("sold", 0);
        assertBucketsConsistent(sku.variant());
    }

    // ---- helpers ----

    private double path(String path) {
        return meters.counter("drop.inventory.hold.path", "path", path).count();
    }

    private com.restless.drop.application.dto.DropReservationResponse.Result hold(Sku sku, Long user) {
        return service.create(sku.drop(), user, UUID.randomUUID().toString(),
                new CreateDropReservationRequest(sku.variant(), "회원", "010-1234-5678", "서울"));
    }

    private String attempt(Sku sku, Long user) {
        try {
            hold(sku, user);
            return "ok";
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private Map<String, Integer> holdConcurrently(Sku sku, List<Long> users) throws Exception {
        var outcomes = new ConcurrentHashMap<String, Integer>();
        var tasks = new ArrayList<Callable<Void>>();
        for (Long user : users) tasks.add(() -> { outcomes.merge(attempt(sku, user), 1, Integer::sum); return null; });
        runAll(tasks, new CountDownLatch(1));
        return outcomes;
    }

    private void runAll(List<Callable<Void>> tasks, CountDownLatch start) throws Exception {
        try (var pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 64))) {
            var futures = new ArrayList<Future<Void>>();
            for (var task : tasks) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown();
            for (var f : futures) f.get(120, TimeUnit.SECONDS);
        }
    }

    private static void assertOnly(Map<String, Integer> outcomes, String... allowed) {
        assertThat(outcomes.keySet()).as("unexpected outcomes (deadlock 1213 or 500 would show here): %s", outcomes)
                .isSubsetOf(List.of(allowed));
    }

    /** Per bucket: conservation, no negatives, reserved and sold equal the holds recorded on that bucket. */
    private void assertBucketsConsistent(Long variant) {
        for (var b : DropStock.buckets(jdbc, variant)) {
            assertThat(b.get("total")).isEqualTo(b.get("available") + b.get("reserved") + b.get("sold"));
            assertThat(b.get("available")).isNotNegative();
            int open = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM drop_reservations WHERE variant_id = ? AND bucket_no = ?
                      AND status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING')""",
                    Integer.class, variant, b.get("bucket_no"));
            assertThat(b.get("reserved")).as("bucket %s reserved", b.get("bucket_no")).isEqualTo(open);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM drop_reservations r JOIN drop_inventories i ON i.variant_id = r.variant_id
                WHERE r.variant_id = ? AND r.bucket_no >= i.bucket_count""", Integer.class, variant)).isZero();
    }

    /**
     * TX1 inserts its reservation before the bucket step (#322), so an uncommitted reservation for this drop means a
     * TX1 is at, or about to reach, the bucket wait. The test user cannot read performance_schema lock tables.
     */
    private void awaitTx1AtBucketStep(Long drop) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var observer = dataSource.getConnection()) {
            observer.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED);
            try (var count = observer.prepareStatement("SELECT COUNT(*) FROM drop_reservations WHERE drop_id = ?")) {
                count.setLong(1, drop);
                while (System.nanoTime() < deadline) {
                    try (var rows = count.executeQuery()) {
                        if (rows.next() && rows.getInt(1) >= 1) {
                            Thread.sleep(300);
                            return;
                        }
                    }
                    Thread.sleep(20);
                }
            }
        }
        fail("no TX1 reached the bucket step");
    }

    private Sku newSku(int quantity) {
        Long drop = newDrop(quantity);
        return new Sku(drop, jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop));
    }

    private Long newDrop(int... quantities) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var stamp = Timestamp.from(now);
        String name = UUID.randomUUID().toString();
        Long owner = newUsers(1).getFirst();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", name, stamp);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at, is_published) VALUES (?, ?, ?, 'SCHEDULED', ?, TRUE)",
                venue, owner, name, stamp);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, name);
        var skus = new ArrayList<CreateDropRequest.Variant>();
        for (int i = 0; i < quantities.length; i++) skus.add(new CreateDropRequest.Variant("SKU-" + i, "O" + i, 39000L, quantities[i]));
        Long drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)), skus)).dropId();
        admin.publish(drop);
        return drop;
    }

    private List<Long> newUsers(int count) {
        String batch = UUID.randomUUID().toString().substring(0, 8);
        var stamp = Timestamp.from(Instant.now());
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < count; i++) rows.add(new Object[] {batch + "-" + i + "@bucket.test", stamp, stamp});
        jdbc.batchUpdate("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'bucket', 'USER', 'ACTIVE', ?, ?)""", rows);
        return jdbc.queryForList("SELECT id FROM users WHERE email LIKE ? ORDER BY id", Long.class, batch + "-%");
    }
}
