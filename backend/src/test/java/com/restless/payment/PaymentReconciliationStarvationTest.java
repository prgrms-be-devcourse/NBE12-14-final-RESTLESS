package com.restless.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.PaymentReconciliationTest.MutableClock;
import com.restless.payment.PaymentReconciliationTest.ObservedPg;
import com.restless.payment.application.*;
import com.restless.payment.application.payable.*;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.*;
import com.restless.payment.infrastructure.fake.FakePgClient;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootTest(properties = {
    "pg.provider=fake",
    "spring.docker.compose.enabled=false",
    "spring.main.allow-bean-definition-overriding=true",
    "restless.payment.reconcile.enabled=false",
    "restless.payment.reconcile.batch-size=2"
})
@Import({TestcontainersConfiguration.class, PaymentReconciliationTest.Config.class})
class PaymentReconciliationStarvationTest {
    static final Instant NOW = PaymentFlowTest.NOW;
    @Autowired PaymentService service;
    @Autowired PaymentReconciler reconciler;
    @MockitoSpyBean PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired PaymentFlowTest.Handler handler;
    @Autowired ObservedPg pg;
    @Autowired FakePgClient fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager entityManager;
    Long user;

    @BeforeEach
    void setup() {
        refunds.deleteAllInBatch();
        payments.deleteAllInBatch();
        clock.now = NOW;
        jdbc.update(
                "INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)"
                        + " VALUES (?, 'hash','reconcile','USER','ACTIVE',?,?)",
                UUID.randomUUID() + "@pay.test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        user = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS test_payment_handler_effects (payment_id BIGINT NOT"
                        + " NULL, stage VARCHAR(20) NOT NULL)");
        jdbc.update("DELETE FROM test_payment_handler_effects");
        handler.reset(new PayableSnapshot(user, 39000, "item", NOW.plusSeconds(300)));
        fake.reset();
        pg.reset();
    }

    PreparePaymentResult prepare(long target) {
        return service.prepare(
                new PreparePaymentCommand(
                        user, PayableType.DROP, target, UUID.randomUUID().toString()));
    }

    Payment payment(PreparePaymentResult p) {
        return payments.findById(p.paymentId()).orElseThrow();
    }

    void change(PreparePaymentResult p, Consumer<Payment> action) {
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        s ->
                                action.accept(
                                        payments.findByIdForUpdate(p.paymentId()).orElseThrow()));
    }

    void processing(PreparePaymentResult p) {
        change(p, x -> x.startProcessing("key-" + p.paymentId(), NOW));
    }

    PgQueryResult.Approved approved(PreparePaymentResult p) {
        return new PgQueryResult.Approved("key-" + p.paymentId(), p.pgOrderId(), 39000, NOW);
    }

    void age(long seconds) {
        clock.now = NOW.plusSeconds(seconds);
    }

    @Test
    void failedApprovalBatchDoesNotStarveNextPayment() {
        var first = prepare(1);
        var second = prepare(2);
        var third = prepare(3);
        for (var p : List.of(first, second, third)) {
            processing(p);
            pg.results.put(p.pgOrderId(), approved(p));
        }
        pg.duringQuery =
                () -> handler.throwApproved = !third.pgOrderId().equals(pg.queriedOrderId);
        age(31);
        double before = failure("apply");

        reconciler.runOnce();
        assertThat(payment(third).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        reconciler.runOnce();

        assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment(third).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(failure("apply")).isEqualTo(before + 2);
        assertThat(pg.txSeen).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='approved'",
                                Long.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PROCESSING", "UNKNOWN"})
    void failedApprovalWaitsForRetryIntervalWithoutChangingBusinessState(PaymentStatus status) {
        var p = prepare(1);
        processing(p);
        if (status == PaymentStatus.UNKNOWN) change(p, x -> x.markUnknown(NOW));
        pg.results.put(p.pgOrderId(), approved(p));
        handler.throwApproved = true;
        age(31);
        double before = failure("apply");

        reconciler.runOnce();
        var checked = payment(p);
        assertThat(checked.getStatus()).isEqualTo(status);
        assertThat(checked.getUpdatedAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(checked.getApprovalStartedAt()).isEqualTo(NOW);
        assertThat(checked.getApprovedAt()).isNull();
        assertThat(checked.getUnknownSinceAt())
                .isEqualTo(status == PaymentStatus.UNKNOWN ? NOW : null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects", Long.class))
                .isZero();
        assertThat(handler.failed).isEmpty();
        assertThat(handler.unknown).isEmpty();

        long interval = status == PaymentStatus.PROCESSING ? 30 : 10;
        age(31 + interval - 1);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(1);
        age(31 + interval);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(2);
        assertThat(payment(p).getStatus()).isEqualTo(status);
        assertThat(failure("apply")).isEqualTo(before + 2);
    }

    @Test
    void failedCheckRecordingDoesNotEscapeOrBlockNextPayment() {
        var first = prepare(1);
        var second = prepare(2);
        for (var p : List.of(first, second)) {
            processing(p);
            pg.results.put(p.pgOrderId(), approved(p));
        }
        pg.duringQuery = () -> handler.throwApproved = first.pgOrderId().equals(pg.queriedOrderId);
        age(31);
        double applyBefore = failure("apply");
        double checkBefore = failure("record_check");
        // First locked read applies approval; the second is the separate cadence transaction.
        var lockedReads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (lockedReads.incrementAndGet() > 1)
                                throw new IllegalStateException("test check failure");
                            return Optional.ofNullable(
                                    entityManager.find(
                                            Payment.class,
                                            first.paymentId(),
                                            LockModeType.PESSIMISTIC_WRITE));
                        })
                .when(payments)
                .findByIdForUpdate(first.paymentId());
        try {
            assertThatCode(reconciler::runOnce).doesNotThrowAnyException();
            assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(payment(first).getUpdatedAt()).isEqualTo(NOW);
            assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(failure("apply")).isEqualTo(applyBefore + 1);
            assertThat(failure("record_check")).isEqualTo(checkBefore + 1);
        } finally {
            reset(payments);
        }
    }

    @Test
    void checkDoesNotTouchPaymentWhoseStatusChangedBeforeRecording() {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), approved(p));
        handler.throwApproved = true;
        age(31);
        double checkBefore = failure("record_check");
        // Another server moves the payment on after the apply rolls back, before the check locks it.
        var lockedReads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (lockedReads.incrementAndGet() > 1) {
                                var other = new TransactionTemplate(tm);
                                other.setPropagationBehavior(
                                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                                other.executeWithoutResult(
                                        s ->
                                                entityManager
                                                        .find(
                                                                Payment.class,
                                                                p.paymentId(),
                                                                LockModeType.PESSIMISTIC_WRITE)
                                                        .markUnknown(NOW.plusSeconds(40)));
                            }
                            return Optional.ofNullable(
                                    entityManager.find(
                                            Payment.class,
                                            p.paymentId(),
                                            LockModeType.PESSIMISTIC_WRITE));
                        })
                .when(payments)
                .findByIdForUpdate(p.paymentId());
        try {
            reconciler.runOnce();
            assertThat(lockedReads.get()).isEqualTo(2);
            var after = payment(p);
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(after.getUpdatedAt()).isEqualTo(NOW.plusSeconds(40));
            assertThat(after.getUnknownSinceAt()).isEqualTo(NOW.plusSeconds(40));
            assertThat(failure("record_check")).isEqualTo(checkBefore);
            assertThat(handler.failed).isEmpty();
        } finally {
            reset(payments);
        }
    }

    double failure(String stage) {
        var counter = meters.find("payment.reconcile.failure").tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }
}
