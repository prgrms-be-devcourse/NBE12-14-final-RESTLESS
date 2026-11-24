package com.restless.drop.application;

import com.restless.drop.acceptance.DropStock;
import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.payment.application.*;
import com.restless.payment.domain.*;
import com.restless.payment.application.pg.PgQueryResult;
import com.restless.payment.infrastructure.fake.FakePgClient;
import com.restless.payment.application.pg.PgApproveCommand;


import com.restless.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

// This test covers payment settlement, not queue admission.
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false", "restless.payment.reconcile.enabled=false",
        "restless.payment.reconcile.processing-stale-after=30s", "restless.drop.expiry.enabled=false",
        "restless.drop.queue.scheduler.enabled=false"})
@Import({TestcontainersConfiguration.class, DropSettlementMismatchRecoveryIntegrationTest.Config.class})
class DropSettlementMismatchRecoveryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService holds;
    @Autowired PaymentService payments;
    @Autowired PaymentReconciler reconciler;
    @Autowired FakePgClient pg;
    @Autowired DropReservationLifecycleService lifecycle;
    @Autowired DropReservationExpiryJob job;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;
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
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at, is_published) VALUES (?, ?, ?, 'SCHEDULED', ?, TRUE)", venue, user, name, timestamp);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, name);
        drop = admin.create(new CreateDropRequest(concert, "상품".repeat(100), "설명", "url", now.minusSeconds(60), now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 39000L, 1)))).dropId();
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

    // INV-01: preserve the bucket CHECK while making the hold's bucket (bucket 0, K = 1) disagree with its hold.
    // INV-02, INV-05, INV-09, INV-10, INV-11, INV-15: no partial settlement or release.
    @Test
    void inv09PermanentSettlementMismatchRetriesWithoutBlockingOthersUntilStockIsRepaired() {
        var broken = processing(reservation, order);
        stock(0, 1, 0);
        assertThat(jdbc.update("UPDATE drop_inventory_buckets SET reserved_quantity = 0, available_quantity = 1 WHERE variant_id = ? AND bucket_no = 0", variant))
                .isEqualTo(1);
        var brokenStock = jdbc.queryForMap("SELECT * FROM drop_inventory_buckets WHERE variant_id = ?", variant);

        // Another SKU in the same drop: the first failing payment precedes this healthy one.
        var timestamp = Timestamp.from(now);
        jdbc.update("INSERT INTO drop_variants (drop_id, sku_code, option_name, price_amount, is_active) VALUES (?, ?, 'L', 39000, true)",
                drop, UUID.randomUUID().toString());
        Long healthyVariant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ? AND option_name = 'L'", Long.class, drop);
        jdbc.update("INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, bucket_count) VALUES (?, 1, 1, 0, 0, 1)", healthyVariant);
        jdbc.update("INSERT INTO drop_inventory_buckets (variant_id, bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity) VALUES (?, 0, 1, 1, 0, 0)", healthyVariant);
        // The purchase slot is per user/drop, so use another buyer.
        String email = UUID.randomUUID() + "@d3.test";
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at) VALUES (?, 'hash', 'healthy', 'USER', 'ACTIVE', ?, ?)", email, timestamp, timestamp);
        Long healthyUser = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var healthyHold = holds.create(drop, healthyUser, UUID.randomUUID().toString(),
                new CreateDropReservationRequest(healthyVariant, "회원", "010", "서울")).body();
        var healthy = processing(healthyHold.reservationId(), healthyHold.orderId(), healthyUser);
        assertThat(broken.paymentId()).isLessThan(healthy.paymentId());
        double failures = applyFailures();

        // Candidate boundary is updated_at <= now - 30s, including exact equality.
        clock.now = now.plusSeconds(30);
        reconciler.runOnce();
        pending(broken.paymentId());
        assertThat(applyFailures()).isEqualTo(failures + 1);
        assertThat(payments.get(healthy.paymentId(), healthyUser).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, healthyHold.reservationId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, healthyHold.orderId())).isEqualTo("PAID");
        assertThat(DropStock.totals(jdbc, healthyVariant))
                .containsEntry("available", 0).containsEntry("reserved", 0).containsEntry("sold", 1);
        assertThat(jdbc.queryForObject("SELECT active_reservation_id FROM drop_purchase_slots WHERE drop_id = ? AND user_id = ?", Long.class, drop, healthyUser)).isNull();

        for (int attempt = 2; attempt <= 6; attempt++) {
            Instant lastCheck = clock.instant();
            assertThat(jdbc.queryForObject("SELECT updated_at FROM payments WHERE id = ?", Timestamp.class, broken.paymentId()).toInstant()).isEqualTo(lastCheck);
            clock.now = lastCheck.plusSeconds(30).minusMillis(1);
            reconciler.runOnce();
            pending(broken.paymentId());
            assertThat(applyFailures()).isEqualTo(failures + attempt - 1);
            clock.now = lastCheck.plusSeconds(30);
            reconciler.runOnce();
            pending(broken.paymentId());
            assertThat(applyFailures()).isEqualTo(failures + attempt);
            assertThat(jdbc.queryForMap("SELECT * FROM drop_inventory_buckets WHERE variant_id = ?", variant)).isEqualTo(brokenStock);
            assertThat(pg.query(broken.pgOrderId())).isInstanceOf(PgQueryResult.Approved.class);
            // These gauges only include UNKNOWN, so they miss this PROCESSING failure.
            assertThat(meters.get("payment.unknown.count").gauge().value()).isZero();
            assertThat(meters.get("payment.unknown.oldest.age.seconds").gauge().value()).isZero();
        }

        Instant expiresAt = jdbc.queryForObject("SELECT expires_at FROM drop_reservations WHERE id = ?", Timestamp.class, reservation).toInstant();
        clock.now = expiresAt.plusSeconds(1);
        assertThatThrownBy(() -> lifecycle.cancel(drop, user, reservation))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DROP_INVALID_STATE));
        job.runOnce();
        pending(broken.paymentId());
        assertThat(applyFailures()).isEqualTo(failures + 6);
        assertThat(jdbc.queryForMap("SELECT * FROM drop_inventory_buckets WHERE variant_id = ?", variant)).isEqualTo(brokenStock);

        assertThat(jdbc.update("UPDATE drop_inventory_buckets SET reserved_quantity = 1, available_quantity = 0 WHERE variant_id = ? AND bucket_no = 0", variant)).isEqualTo(1);
        clock.now = clock.instant().plusSeconds(30);
        reconciler.runOnce();
        confirmed(broken.paymentId());
        reconciler.runOnce();
        confirmed(broken.paymentId());
        assertThat(applyFailures()).isEqualTo(failures + 6);
    }

    private PreparePaymentResult processing(Long hold, Long orderId) {
        return processing(hold, orderId, user);
    }

    private PreparePaymentResult processing(Long hold, Long orderId, Long buyer) {
        var prepared = payments.prepare(new PreparePaymentCommand(buyer, PayableType.DROP, hold, UUID.randomUUID().toString()));
        // Persist the state after TX2 and PG approval but before TX3, simulating a crash.
        String key = "test-key-" + UUID.randomUUID();
        assertThat(jdbc.update("UPDATE payments SET status = 'PROCESSING', payment_key = ?, approval_started_at = ?, updated_at = ? WHERE id = ?", key, Timestamp.from(now), Timestamp.from(now), prepared.paymentId())).isEqualTo(1);
        assertThat(jdbc.update("UPDATE drop_reservations SET status = 'CONFIRMING', current_payment_id = ?, updated_at = ? WHERE id = ?", prepared.paymentId(), Timestamp.from(now), hold)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE orders SET status = 'PAYMENT_CONFIRMING' WHERE id = ?", orderId)).isEqualTo(1);
        pg.willApprove(prepared.pgOrderId());
        pg.willDelay(prepared.pgOrderId(), Duration.ZERO);
        pg.approve(new PgApproveCommand(key, prepared.pgOrderId(), prepared.amount(), UUID.randomUUID().toString()));
        assertThat(payments.get(prepared.paymentId(), buyer).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(pg.query(prepared.pgOrderId())).isInstanceOf(PgQueryResult.Approved.class);
        return prepared;
    }

    private double applyFailures() {
        return meters.counter("payment.reconcile.failure", "stage", "apply").count();
    }

    private void pending(Long payment) {
        assertThat(payments.get(payment, user).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(status()).isEqualTo("CONFIRMING");
        assertThat(orderStatus()).isEqualTo("PAYMENT_CONFIRMING");
        assertThat(jdbc.queryForObject("SELECT current_payment_id FROM drop_reservations WHERE id = ?", Long.class, reservation)).isEqualTo(payment);
        stock(1, 0, 0);
        assertThat(slot()).isEqualTo(reservation);
    }

    private void confirmed(Long payment) {
        assertThat(payments.get(payment, user).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(orderStatus()).isEqualTo("PAID");
        stock(0, 0, 1);
        assertThat(slot()).isNull();
    }

    private String status() { return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, reservation); }
    private String orderStatus() { return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order); }
    private Long slot() {
        return jdbc.queryForObject("SELECT active_reservation_id FROM drop_purchase_slots WHERE drop_id = ? AND user_id = ?", Long.class, drop, user);
    }
    private void stock(int available, int reserved, int sold) {
        assertThat(DropStock.totals(jdbc, variant)).containsEntry("total", 1).containsEntry("available", available)
                .containsEntry("reserved", reserved).containsEntry("sold", sold);
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
