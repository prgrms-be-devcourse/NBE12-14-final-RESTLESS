package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.acceptance.DropStock;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.payment.application.payable.*;
import com.restless.payment.domain.PaymentRefundKind;
import java.sql.Timestamp;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * INV-03, INV-11, INV-12 per bucket (ADR-0005): the hold records the bucket it took, and every settlement path
 * changes only that bucket. Bucket 0 starts empty so a correct hold can never be recorded as the default 0.
 */
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false", "restless.drop.inventory.bucket-count=4"})
@Import({TestcontainersConfiguration.class, DropInventoryBucketSettlementIntegrationTest.Config.class})
class DropInventoryBucketSettlementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService holds;
    @Autowired DropPaymentResultHandler handler;
    @Autowired DropReservationLifecycleService lifecycle;
    @Autowired DropReservationExpiryJob job;
    @Autowired PlatformTransactionManager tm;
    @Autowired MutableClock clock;
    final Instant now = Instant.parse("2026-11-20T03:00:00Z");
    Long user, drop, variant, reservation, order;
    int bucket;
    List<Map<String, Integer>> before;

    @BeforeEach
    void seed() {
        clock.now = now;
        var timestamp = Timestamp.from(now);
        user = newUser();
        var name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", name, timestamp);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at, is_published) VALUES (?, ?, ?, 'SCHEDULED', ?, TRUE)", venue, user, name, timestamp);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, name);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now.minusSeconds(60), now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 39000L, 8)))).dropId();
        admin.publish(drop);
        variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop);
        DropStock.setAvailableByBucket(jdbc, variant, 0, 2, 2, 2);
        before = DropStock.buckets(jdbc, variant);
        var hold = holds.create(drop, user, UUID.randomUUID().toString(),
                new CreateDropReservationRequest(variant, "회원", "010", "서울")).body();
        reservation = hold.reservationId();
        order = hold.orderId();
        bucket = DropStock.bucketOf(jdbc, reservation);
    }

    @Test
    void holdRecordsTheBucketItActuallyTook() {
        assertThat(bucket).isBetween(1, 3);
        assertOnlyHoldBucket(-1, +1, 0);
    }

    @Test
    void expiryReturnsToTheHoldBucket() {
        clock.now = now.plus(Duration.ofMinutes(10));
        job.runOnce();
        assertThat(status()).isEqualTo("EXPIRED");
        assertOnlyHoldBucket(0, 0, 0);
    }

    @Test
    void cancelReturnsToTheHoldBucket() {
        lifecycle.cancel(drop, user, reservation);
        assertThat(status()).isEqualTo("RELEASED");
        assertOnlyHoldBucket(0, 0, 0);
    }

    @Test
    void failureAfterDeadlineReturnsToTheHoldBucket() {
        begin(101);
        clock.now = now.plus(Duration.ofMinutes(10));
        tx(() -> { handler.onFailed(reservation, attempt(101)); handler.onFailed(reservation, attempt(101)); return null; });
        assertThat(status()).isEqualTo("RELEASED");
        assertOnlyHoldBucket(0, 0, 0);
    }

    @Test
    void compensationReturnsToTheHoldBucketOnce() {
        begin(101);
        assertThat(tx(() -> handler.onApproved(reservation, approval(101, 39001))).rejected()).isTrue();
        assertThat(status()).isEqualTo("COMPENSATING");
        assertOnlyHoldBucket(-1, +1, 0);
        tx(() -> {
            handler.onRefunded(reservation, refund(101, PaymentRefundKind.COMPENSATION));
            handler.onRefunded(reservation, refund(101, PaymentRefundKind.COMPENSATION));
            return null;
        });
        assertThat(status()).isEqualTo("RELEASED");
        assertOnlyHoldBucket(0, 0, 0);
    }

    @Test
    void inv11AndInv12ConfirmThenCustomerRefundRestoreTheHoldBucketExactlyOnce() {
        begin(101);
        assertThat(tx(() -> handler.onApproved(reservation, approval(101, 39000))).accepted()).isTrue();
        assertThat(tx(() -> handler.onApproved(reservation, approval(101, 39000))).accepted()).isTrue();
        assertThat(status()).isEqualTo("CONFIRMED");
        assertOnlyHoldBucket(-1, 0, +1);
        var refund = refund(101, PaymentRefundKind.CUSTOMER_REFUND);
        assertThat(tx(() -> handler.onRefundRequesting(reservation, refund)).accepted()).isTrue();
        tx(() -> { handler.onRefunded(reservation, refund); handler.onRefunded(reservation, refund); return null; });
        assertThat(status()).isEqualTo("RESTORED");
        assertOnlyHoldBucket(0, 0, 0);
    }

    @Test
    void settlementOnAnotherBucketsStockFailsWholly() {
        // INV-03: if the recorded bucket has nothing reserved, release must not borrow from a sibling bucket.
        jdbc.update("UPDATE drop_inventory_buckets SET reserved_quantity = 0, sold_quantity = sold_quantity + 1 WHERE variant_id = ? AND bucket_no = ?",
                variant, bucket);
        var stock = DropStock.buckets(jdbc, variant);
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.cancel(drop, user, reservation));
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(DropStock.buckets(jdbc, variant)).isEqualTo(stock);
    }

    // ---- helpers ----

    /** The hold's bucket moved by (available, reserved, sold) from the start; every other bucket is unchanged. */
    private void assertOnlyHoldBucket(int available, int reserved, int sold) {
        var expected = new ArrayList<Map<String, Integer>>();
        for (var b : before) {
            if (b.get("bucket_no") != bucket) { expected.add(b); continue; }
            expected.add(Map.of("bucket_no", bucket, "total", b.get("total"), "available", b.get("available") + available,
                    "reserved", b.get("reserved") + reserved, "sold", b.get("sold") + sold));
        }
        assertThat(DropStock.buckets(jdbc, variant)).isEqualTo(expected);
    }

    private Long newUser() {
        var email = UUID.randomUUID() + "@bucket.test";
        var timestamp = Timestamp.from(now);
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at) VALUES (?, 'hash', 'b', 'USER', 'ACTIVE', ?, ?)", email, timestamp, timestamp);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private <T> T tx(Supplier<T> action) {
        var tx = new TransactionTemplate(tm);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> action.get());
    }

    private PaymentAttemptContext attempt(long id) { return new PaymentAttemptContext(id, now); }
    private ApprovedPayment approval(long id, long amount) {
        return new ApprovedPayment(id, "test-order", "test-key", amount, now, clock.instant());
    }
    private RefundContext refund(long id, PaymentRefundKind kind) { return new RefundContext(id, kind); }
    private void begin(long id) { assertThat(tx(() -> handler.onApprovalStarting(reservation, attempt(id))).accepted()).isTrue(); }
    private String status() { return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, reservation); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean @Primary MutableClock bucketTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
