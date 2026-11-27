package com.restless.drop.application;

import com.restless.drop.acceptance.DropStock;
import com.restless.TestcontainersConfiguration;
import com.restless.support.RecordedLogs;
import com.restless.drop.application.DropPaymentTestClockConfig.MutableClock;
import com.restless.common.error.BusinessException;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.payment.application.*;
import com.restless.payment.domain.*;
import com.restless.payment.application.pg.PgQueryResult;
import com.restless.payment.infrastructure.fake.FakePgClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

// This test covers payment settlement, not queue admission.
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false"})
@Import({TestcontainersConfiguration.class, DropPaymentTestClockConfig.class})
class DropApprovalRollbackRecoveryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService holds;
    @Autowired PaymentService payments;
    @Autowired PaymentReconciler reconciler;
    @Autowired FakePgClient pg;
    @MockitoSpyBean DropReservationSettlement settlement;
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
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)", venue, user, name, timestamp);
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

    // INV-01, INV-02, INV-05, INV-09, INV-10, INV-11, INV-15.
    @Test
    void inv09Inv10ApprovalRollbackKeepsHoldUntilRecoveryAndInv11SettlesOnce() {
        assertThat(status()).isEqualTo("RESERVED");
        stock(0, 1, 0);
        var prepared = payments.prepare(new PreparePaymentCommand(
                user, PayableType.DROP, reservation, UUID.randomUUID().toString()));
        pg.willApprove(prepared.pgOrderId());
        pg.willDelay(prepared.pgOrderId(), Duration.ZERO);
        double failures = meters.counter("payment.result.apply.failure").count();
        DropReservationSettlement target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(settlement);
        doThrow(new IllegalStateException("Injected TX3 rollback"))
                .doCallRealMethod().when(target).confirm(any());
        try (var logs = new RecordedLogs(PaymentService.class)) {
            var result = payments.confirm(new ConfirmPaymentCommand(user,
                    "test-key-" + UUID.randomUUID(), prepared.pgOrderId(), prepared.amount()));
            assertThat(result.status()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(logs.entries()).singleElement().asString()
                    .contains("ERROR Payment result transaction rolled back paymentId=" + prepared.paymentId())
                    .doesNotContain("test-key-", "Injected TX3 rollback");
        }
        assertThat(pg.query(prepared.pgOrderId())).isInstanceOf(PgQueryResult.Approved.class);
        pending(prepared.paymentId());
        assertThat(meters.counter("payment.result.apply.failure").count()).isEqualTo(failures + 1);
        verify(target, times(1)).confirm(any());

        Instant expiresAt = jdbc.queryForObject("SELECT expires_at FROM drop_reservations WHERE id = ?",
                Timestamp.class, reservation).toInstant();
        clock.now = expiresAt.plusSeconds(1);
        job.runOnce();
        pending(prepared.paymentId());
        assertThatThrownBy(() -> lifecycle.cancel(drop, user, reservation))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DROP_INVALID_STATE));
        pending(prepared.paymentId());

        clock.now = clock.instant().plusSeconds(31);
        try (var logs = new RecordedLogs(PaymentTransactions.class)) {
            reconciler.runOnce();
            confirmed(prepared.paymentId());
            verify(target, times(2)).confirm(any());
            reconciler.runOnce();
            confirmed(prepared.paymentId());
            verify(target, times(2)).confirm(any());
            assertThat(logs.entries()).containsExactly(
                    "INFO event=drop.payment.approval_transition paymentId=" + prepared.paymentId()
                            + " from=PROCESSING to=APPROVED");
        }
        assertThat(meters.counter("payment.result.apply.failure").count()).isEqualTo(failures + 1);
    }

    private void pending(Long payment) {
        assertThat(payments.get(payment, user).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(status()).isEqualTo("CONFIRMING");
        assertThat(orderStatus()).isEqualTo("PAYMENT_CONFIRMING");
        assertThat(jdbc.queryForObject("SELECT current_payment_id FROM drop_reservations WHERE id = ?",
                Long.class, reservation)).isEqualTo(payment);
        stock(0, 1, 0);
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
}
