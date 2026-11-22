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
class PaymentCancelReconciliationStarvationTest {
    static final Instant NOW = PaymentFlowTest.NOW;
    @Autowired PaymentService service;
    @Autowired PaymentReconciler reconciler;
    @MockitoSpyBean PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @MockitoSpyBean PaymentFlowTest.Handler handler;
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

    void age(long seconds) {
        clock.now = NOW.plusSeconds(seconds);
    }

    PaymentRefund cancellation(PreparePaymentResult p, PaymentRefundKind kind, boolean unknown) {
        service.confirm(
                new ConfirmPaymentCommand(user, "key-" + p.paymentId(), p.pgOrderId(), 39000));
        return new TransactionTemplate(tm)
                .execute(
                        s -> {
                            var payment = payments.findByIdForUpdate(p.paymentId()).orElseThrow();
                            payment.requestCancel(NOW);
                            var refund =
                                    refunds.saveAndFlush(
                                            PaymentRefund.request(
                                                    p.paymentId(),
                                                    kind,
                                                    39000,
                                                    "cancel-" + p.paymentId(),
                                                    "reason",
                                                    NOW));
                            if (unknown) {
                                payment.markCancelUnknown(NOW);
                                refund.markUnknown(NOW);
                            }
                            return refund;
                        });
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCEL_PENDING", "CANCEL_UNKNOWN"})
    void failedCancellationBatchDoesNotStarveNextPayment(PaymentStatus status) {
        checkBatch(status, false, false);
    }

    @Test
    void missingRefundBatchDoesNotStarveNextPayment() {
        checkBatch(PaymentStatus.CANCEL_PENDING, true, false);
    }

    void checkBatch(PaymentStatus status, boolean missingRefund, boolean retry) {
        var first = prepare(1);
        var second = prepare(2);
        var third = prepare(3);
        for (var p : List.of(first, second, third)) {
            cancellation(
                    p, PaymentRefundKind.CUSTOMER_REFUND, status == PaymentStatus.CANCEL_UNKNOWN);
            if (missingRefund && p != third)
                jdbc.update("DELETE FROM payment_refunds WHERE payment_id=?", p.paymentId());
            pg.results.put(
                    p.pgOrderId(),
                    retry
                            ? new PgQueryResult.Approved(
                                    "key-" + p.paymentId(), p.pgOrderId(), 39000, NOW)
                            : new PgQueryResult.Cancelled("cancel-tx", NOW));
        }
        doAnswer(
                        invocation -> {
                            RefundContext refund = invocation.getArgument(1);
                            handler.throwRefunded = !third.paymentId().equals(refund.paymentId());
                            return invocation.callRealMethod();
                        })
                .when(handler)
                .onRefunded(anyLong(), any(RefundContext.class));
        age(31);
        reconciler.runOnce();
        for (var p : List.of(first, second, third))
            assertThat(payment(p).getStatus()).isEqualTo(status);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isZero();
        reconciler.runOnce();
        assertThat(payment(third).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        for (var p : List.of(first, second)) {
            assertThat(payment(p).getStatus()).isEqualTo(status);
            assertThat(payment(p).getUpdatedAt()).isEqualTo(clock.instant());
        }
        assertThat(pg.txSeen).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCEL_PENDING", "CANCEL_UNKNOWN"})
    void failedRetriedCancellationBatchDoesNotStarveNextPayment(PaymentStatus status) {
        checkBatch(status, false, true);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCEL_PENDING", "CANCEL_UNKNOWN"})
    void failedApplyPreservesRefundAndWaitsForRetryInterval(PaymentStatus status) {
        var p = prepare(1);
        var refund =
                cancellation(
                        p, PaymentRefundKind.CUSTOMER_REFUND, status == PaymentStatus.CANCEL_UNKNOWN);
        pg.results.put(p.pgOrderId(), new PgQueryResult.Cancelled("cancel-tx", NOW));
        handler.throwRefunded = true;
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(status);
        assertThat(payment(p).getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(refunds.findById(refund.getId()).orElseThrow().getStatus())
                .isEqualTo(
                        status == PaymentStatus.CANCEL_UNKNOWN
                                ? PaymentRefundStatus.UNKNOWN
                                : PaymentRefundStatus.REQUESTED);
        long interval = status == PaymentStatus.CANCEL_UNKNOWN ? 10 : 30;
        age(31 + interval - 1);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(1);
        age(31 + interval);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(2);
        assertThat(payment(p).getStatus()).isEqualTo(status);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isZero();
    }

    @Test
    void failedPreparationRecordsCheckAndDoesNotStarveNextPayment() {
        var first = prepare(1);
        var second = prepare(2);
        var third = prepare(3);
        for (var p : List.of(first, second, third)) {
            cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
            pg.results.put(p.pgOrderId(), new PgQueryResult.Cancelled("cancel-tx", NOW));
        }
        for (var p : List.of(first, second)) {
            var reads = new AtomicInteger();
            doAnswer(
                            invocation -> {
                                if (reads.incrementAndGet() == 1)
                                    throw new IllegalStateException("prepare unavailable");
                                return Optional.of(
                                        entityManager.find(
                                                Payment.class,
                                                p.paymentId(),
                                                LockModeType.PESSIMISTIC_WRITE));
                            })
                    .when(payments)
                    .findByIdForUpdate(p.paymentId());
        }
        age(31);
        double before = failure("cancel_prepare");
        reconciler.runOnce();
        assertThat(payment(first).getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(payment(second).getUpdatedAt()).isEqualTo(clock.instant());
        reconciler.runOnce();
        assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(payment(third).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(failure("cancel_prepare")).isEqualTo(before + 2);
    }

    @Test
    void failedCheckRecordingDoesNotEscapeOrBlockNextPayment() {
        var first = prepare(1);
        var second = prepare(2);
        for (var p : List.of(first, second)) {
            cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
            pg.results.put(p.pgOrderId(), new PgQueryResult.Cancelled("cancel-tx", NOW));
        }
        doThrow(new IllegalStateException("callback unavailable"))
                .when(handler)
                .onRefunded(eq(1L), any(RefundContext.class));
        var reads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() > 2)
                                throw new IllegalStateException("check unavailable");
                            return Optional.of(
                                    entityManager.find(
                                            Payment.class,
                                            first.paymentId(),
                                            LockModeType.PESSIMISTIC_WRITE));
                        })
                .when(payments)
                .findByIdForUpdate(first.paymentId());
        age(31);
        double before = failure("record_check");
        assertThatCode(reconciler::runOnce).doesNotThrowAnyException();
        assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(payment(first).getUpdatedAt()).isEqualTo(NOW);
        assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(failure("record_check")).isEqualTo(before + 1);
    }

    @Test
    void refundMissingAfterQueryRecordsCheckWithoutCallback() {
        var p = prepare(1);
        cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
        pg.results.put(
                p.pgOrderId(),
                new PgQueryResult.Approved("key-" + p.paymentId(), p.pgOrderId(), 39000, NOW));
        pg.duringQuery =
                () -> jdbc.update("DELETE FROM payment_refunds WHERE payment_id=?", p.paymentId());
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(payment(p).getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(handler.refunded).isEmpty();
        assertThat(pg.cancels.get()).isZero();
    }

    @Test
    void checkDoesNotTouchPaymentWhoseStatusChangedDuringQuery() {
        var p = prepare(1);
        cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
        pg.results.put(p.pgOrderId(), new PgQueryResult.Unknown("unresolved"));
        pg.duringQuery = () -> change(p, x -> x.revertCancel(NOW.plusSeconds(30)));
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment(p).getUpdatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(handler.refunded).isEmpty();
    }

    double failure(String stage) {
        var counter = meters.find("payment.reconcile.failure").tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }
}
