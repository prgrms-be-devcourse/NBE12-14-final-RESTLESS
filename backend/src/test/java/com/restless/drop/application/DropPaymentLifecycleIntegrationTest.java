package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.payment.application.payable.*;
import com.restless.payment.domain.PaymentRefundKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

// This test covers payment settlement, not queue admission.
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false"})
@Import({TestcontainersConfiguration.class, DropPaymentLifecycleIntegrationTest.Config.class})
class DropPaymentLifecycleIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService holds;
    @Autowired DropPaymentResultHandler handler;
    @Autowired DropReservationLifecycleService lifecycle;
    @Autowired DropReservationExpiryJob job;
    @Autowired PlatformTransactionManager tm;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;
    @Autowired ApplicationContext context;
    final Instant now = Instant.parse("2026-11-20T03:00:00Z");
    Long user, drop, variant, reservation, order;

    @BeforeEach
    void seed() {
        clock.now = now;
        var email = UUID.randomUUID() + "@d3.test";
        var timestamp = Timestamp.from(now);
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at) VALUES (?, 'hash', 'd3', 'USER', 'ACTIVE', ?, ?)", email, timestamp, timestamp);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", name, timestamp);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)", venue, user, name, timestamp);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, name);
        drop = admin.create(new CreateDropRequest(concert, "상품".repeat(100), "설명", "url", now.minusSeconds(60), now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 39000L, 3)))).dropId();
        admin.publish(drop);
        variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop);
        var hold = newHold();
        reservation = hold.reservationId();
        order = hold.orderId();
    }

    private com.restless.drop.application.dto.DropReservationResponse.Created newHold() {
        return holds.create(drop, user, UUID.randomUUID().toString(),
                new CreateDropReservationRequest(variant, "회원", "010", "서울")).body();
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
    private void approve(long id) { assertThat(tx(() -> handler.onApproved(reservation, approval(id, 39000))).accepted()).isTrue(); }
    private String status() { return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, reservation); }
    private String orderStatus() { return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order); }
    private void stock(int available, int reserved, int sold) {
        assertThat(jdbc.queryForMap("SELECT available_quantity, reserved_quantity, sold_quantity FROM drop_inventories WHERE variant_id = ?", variant))
                .containsEntry("available_quantity", available).containsEntry("reserved_quantity", reserved).containsEntry("sold_quantity", sold);
    }

    @Test
    void inv15EveryStaleCallbackLeavesCurrentPurchaseUntouched() {
        begin(101);
        tx(() -> { handler.onFailed(reservation, attempt(101)); return null; });
        begin(102);
        assertThat(tx(() -> handler.onApproved(reservation, approval(101, 39000))).rejected()).isTrue();
        tx(() -> {
            handler.onUnknown(reservation, attempt(101));
            handler.onFailed(reservation, attempt(101));
            handler.onRefunded(reservation, refund(101, PaymentRefundKind.COMPENSATION));
            return null;
        });
        assertThat(status()).isEqualTo("CONFIRMING");
        assertThat(orderStatus()).isEqualTo("PAYMENT_CONFIRMING");
        stock(2, 1, 0);
        approve(102);
        assertThat(status()).isEqualTo("CONFIRMED");
    }

    @Test
    void inv11LateApprovalAndDuplicateCallbacksApplyOnlyOnce() {
        begin(101);
        clock.now = now.plusSeconds(600);
        tx(() -> { handler.onUnknown(reservation, attempt(101)); return null; });
        assertThat(status()).isEqualTo("RECONCILING");
        assertThat(lifecycle.expire(reservation)).isFalse();
        approve(101);
        approve(101);
        tx(() -> { handler.onUnknown(reservation, attempt(101)); handler.onFailed(reservation, attempt(101)); return null; });
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(orderStatus()).isEqualTo("PAID");
        stock(2, 0, 1);
    }

    @Test
    void approvalAtExactDeadlineIsRejectedEvenIfAttemptTimestampIsEarlier() {
        clock.now = now.plusSeconds(300);
        assertThat(tx(() -> handler.onApprovalStarting(reservation, attempt(101))).rejected()).isTrue();
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(lifecycle.expire(reservation)).isTrue();
        assertThat(status()).isEqualTo("EXPIRED");
        stock(3, 0, 0);
    }

    @Test
    void failedAtDeadlineReleasesAndNotStartedOrDuplicateFailuresDoNothing() {
        tx(() -> { handler.onFailed(reservation, PaymentAttemptContext.notStarted(100L)); return null; });
        assertThat(status()).isEqualTo("RESERVED");
        begin(101);
        clock.now = now.plusSeconds(300);
        tx(() -> { handler.onFailed(reservation, attempt(101)); handler.onFailed(reservation, attempt(101)); return null; });
        assertThat(status()).isEqualTo("RELEASED");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
        stock(3, 0, 0);
    }

    @Test
    void compensationKeepsStockUntilMatchingPgCancellationThenReleasesOnce() {
        begin(101);
        assertThat(tx(() -> handler.onApproved(reservation, approval(101, 39001))).rejected()).isTrue();
        assertThat(status()).isEqualTo("COMPENSATING");
        clock.now = now.plusSeconds(600);
        assertThat(lifecycle.expire(reservation)).isFalse();
        var before = meters.counter("drop.payment.result", "event", "refunded", "result", "anomaly").count();
        tx(() -> { handler.onRefunded(reservation, refund(101, PaymentRefundKind.CUSTOMER_REFUND)); return null; });
        assertThat(meters.counter("drop.payment.result", "event", "refunded", "result", "anomaly").count()).isEqualTo(before + 1);
        stock(2, 1, 0);
        tx(() -> {
            handler.onRefunded(reservation, refund(101, PaymentRefundKind.COMPENSATION));
            handler.onRefunded(reservation, refund(101, PaymentRefundKind.COMPENSATION));
            return null;
        });
        assertThat(status()).isEqualTo("RELEASED");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
        stock(3, 0, 0);
    }

    @Test
    void refundRejectionKeepsSoldStockAndRetryRestoresWithoutClearingNewPurchaseSlot() {
        begin(101);
        approve(101);
        var newer = newHold();
        var refund = refund(101, PaymentRefundKind.CUSTOMER_REFUND);
        assertThat(tx(() -> handler.onRefundRequesting(reservation, refund)).accepted()).isTrue();
        tx(() -> { handler.onRefundRejected(reservation, refund); handler.onRefundRejected(reservation, refund); return null; });
        assertThat(orderStatus()).isEqualTo("REFUND_REVIEW");
        stock(1, 1, 1);
        assertThat(tx(() -> handler.onRefundRequesting(reservation, refund)).accepted()).isTrue();
        tx(() -> { handler.onRefunded(reservation, refund); handler.onRefunded(reservation, refund); return null; });
        assertThat(status()).isEqualTo("RESTORED");
        assertThat(orderStatus()).isEqualTo("REFUNDED");
        stock(2, 1, 0);
        assertThat(jdbc.queryForObject("SELECT active_reservation_id FROM drop_purchase_slots WHERE drop_id = ? AND user_id = ?", Long.class, drop, user)).isEqualTo(newer.reservationId());
    }

    @Test
    void callerRollbackRevertsReservationInventoryOrderSlotAndSuccessMetric() {
        begin(101);
        var before = meters.counter("drop.payment.result", "event", "approved", "result", "confirmed").count();
        assertThatIllegalStateException().isThrownBy(() -> tx(() -> {
            handler.onApproved(reservation, approval(101, 39000));
            throw new IllegalStateException("caller failed before commit");
        }));
        assertThat(status()).isEqualTo("CONFIRMING");
        assertThat(orderStatus()).isEqualTo("PAYMENT_CONFIRMING");
        stock(2, 1, 0);
        assertThat(meters.counter("drop.payment.result", "event", "approved", "result", "confirmed").count()).isEqualTo(before);
        approve(101);
    }

    @Test
    void twoExpiryWorkersReleaseOnceAndDisabledSchedulerLeavesRunnableJob() throws Exception {
        assertThat(context.getBeansOfType(DropReservationExpiryScheduler.class)).isEmpty();
        assertThat(context.getBean("dropReservationExpiryJob")).isSameAs(job);
        clock.now = now.plusSeconds(300);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> lifecycle.expire(reservation));
            var b = executor.submit(() -> lifecycle.expire(reservation));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        job.runOnce();
        assertThat(status()).isEqualTo("EXPIRED");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
        stock(3, 0, 0);
    }

    @Test
    void cancellationAndApprovalStartSerializeOnReservation() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = executor.submit(() -> tx(() -> handler.onApprovalStarting(reservation, attempt(101))).accepted());
            var cancel = executor.submit(() -> {
                try { lifecycle.cancel(drop, user, reservation); return true; }
                catch (BusinessException conflict) { return false; }
            });
            var approved = start.get(10, TimeUnit.SECONDS);
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo(!approved);
            if (approved) { assertThat(status()).isEqualTo("CONFIRMING"); stock(2, 1, 0); }
            else { assertThat(status()).isEqualTo("RELEASED"); stock(3, 0, 0); }
        }
    }

    @Test
    void loadUsesServerAmountAndBoundsLongNamesThenDisallowsStartedHold() {
        var loaded = tx(() -> handler.load(reservation)).orElseThrow();
        assertThat(loaded.amount()).isEqualTo(39000);
        assertThat(loaded.ownerUserId()).isEqualTo(user);
        assertThat(loaded.orderName()).hasSize(PayableSnapshot.MAX_ORDER_NAME_LENGTH);
        begin(101);
        assertThat(tx(() -> handler.load(reservation))).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean @Primary MutableClock paymentTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
