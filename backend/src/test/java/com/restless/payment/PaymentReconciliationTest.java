package com.restless.payment;

import static org.assertj.core.api.Assertions.*;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.application.*;
import com.restless.payment.application.payable.*;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.*;
import com.restless.payment.infrastructure.fake.FakePgClient;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(properties = {
    "pg.provider=fake",
    "spring.docker.compose.enabled=false",
    "spring.main.allow-bean-definition-overriding=true",
    // 수동 대사를 검증하므로 IntelliJ JUnit 직접 실행에서도 자동 실행을 끈다.
    "restless.payment.reconcile.enabled=false"
})
@Import({TestcontainersConfiguration.class, PaymentReconciliationTest.Config.class})
class PaymentReconciliationTest {
    static final Instant NOW = PaymentFlowTest.NOW;
    @Autowired PaymentService service;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentReconciler reconciler;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired PaymentFlowTest.Handler handler;
    @Autowired ObservedPg pg;
    @Autowired FakePgClient fake;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager tm;
    @Autowired ApplicationContext context;
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

    ConfirmPaymentResult confirm(PreparePaymentResult p) {
        return service.confirm(
                new ConfirmPaymentCommand(user, "key-" + p.paymentId(), p.pgOrderId(), 39000));
    }

    Payment payment(PreparePaymentResult p) {
        return payments.findById(p.paymentId()).orElseThrow();
    }

    void change(PreparePaymentResult p, java.util.function.Consumer<Payment> action) {
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

    PaymentRefund cancellation(PreparePaymentResult p, PaymentRefundKind kind, boolean unknown) {
        confirm(p);
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

    @Test
    void staleProcessingApprovedUsesStoredAttemptOnce() {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), approved(p));
        age(31);
        reconciler.runOnce();
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(handler.approved).hasSize(1);
        assertThat(handler.approved.getFirst().approvalStartedAt()).isEqualTo(NOW);
        assertThat(pg.approvals.get()).isZero();
        assertThat(pg.txSeen).isFalse();
    }

    @Test
    void lostResponseRecoversActualApprovalWithoutApprovingAgain() {
        var p = prepare(1);
        fake.willLoseResponse(p.pgOrderId());
        confirm(p);
        age(11);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(handler.approved).hasSize(1);
        assertThat(pg.approvals.get()).isEqualTo(1);
    }

    @Test
    void timeoutNotFoundStaysUnknownUntilFinalWindow() {
        var p = prepare(1);
        fake.willTimeout(p.pgOrderId());
        confirm(p);
        age(890);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(handler.failed).isEmpty();
        age(900);
        reconciler.runOnce();
        assertThat(payment(p).getFailureCode()).isEqualTo("PG_NOT_FOUND");
        assertThat(handler.failed).containsExactly(new PaymentAttemptContext(p.paymentId(), NOW));
        assertThat(pg.approvals.get()).isEqualTo(1);
    }

    @Test
    void notApprovedFailsThroughHandler() {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), new PgQueryResult.NotApproved("ABORTED", "DENIED", "denied"));
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment(p).getFailureCode()).isEqualTo("DENIED");
        assertThat(handler.failed).hasSize(1);
    }

    @Test
    void approvedButRejectedCompensates() {
        var p = prepare(1);
        fake.willLoseResponse(p.pgOrderId());
        confirm(p);
        handler.approvedDecision = ApprovalDecision.reject("cannot fulfill");
        age(11);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(refunds.findAll())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
                            assertThat(r.getKind()).isEqualTo(PaymentRefundKind.COMPENSATION);
                        });
        assertThat(handler.refunded)
                .containsExactly(new RefundContext(p.paymentId(), PaymentRefundKind.COMPENSATION));
        assertThat(pg.txSeen).isFalse();
    }

    @Test
    void recentProcessingIsUntouched() {
        var p = prepare(1);
        processing(p);
        age(29);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isZero();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void expiredReadyReleasesUniqueAndUsesNotStartedWithoutPg() {
        var p = prepare(1);
        age(301);
        reconciler.runOnce();
        assertThat(payment(p).getFailureCode()).isEqualTo("EXPIRED");
        assertThat(handler.failed).containsExactly(PaymentAttemptContext.notStarted(p.paymentId()));
        assertThat(pg.queries.get() + pg.approvals.get() + pg.cancels.get()).isZero();
        handler.snapshot =
                new PayableSnapshot(user, 39000, "item", clock.instant().plusSeconds(300));
        assertThat(prepare(1).paymentId()).isNotEqualTo(p.paymentId());
    }

    @Test
    void readyWithoutExpiryIsUntouched() {
        handler.snapshot = new PayableSnapshot(user, 39000, "item", null);
        var p = prepare(1);
        age(10000);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @ParameterizedTest
    @EnumSource(PaymentRefundKind.class)
    void cancelUnknownCompletesWithStoredKind(PaymentRefundKind kind) {
        var p = prepare(1);
        var r = cancellation(p, kind, true);
        fake.cancel(new PgCancelCommand("key-" + p.paymentId(), "reason", r.getIdempotencyKey()));
        age(31);
        reconciler.runOnce();
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(refunds.findById(r.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(handler.refunded).containsExactly(new RefundContext(p.paymentId(), kind));
        assertThat(pg.cancels.get()).isZero();
    }

    @Test
    void staleCancelPendingRetriesSameKeyOutsideTx() {
        var p = prepare(1);
        var r = cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
        age(31);
        var retryCounter = meters.find("payment.reconcile.cancel.retry").counter();
        double retriesBefore = retryCounter == null ? 0 : retryCounter.count();
        new TransactionTemplate(tm).executeWithoutResult(s -> reconciler.runOnce());
        assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(r.getIdempotencyKey());
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(pg.txSeen).isFalse();
        assertThat(meters.get("payment.reconcile.cancel.retry").counter().count())
                .isEqualTo(retriesBefore + 1);
    }

    @Test
    void twoRunsQueryConcurrentlyButApplyOnce() throws Exception {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), approved(p));
        age(31);
        var barrier = new CyclicBarrier(2);
        pg.duringQuery = () -> await(barrier);
        concurrent(reconciler::runOnce, reconciler::runOnce);
        assertThat(pg.queries.get()).isEqualTo(2);
        assertThat(handler.approved).hasSize(1);
    }

    @Test
    void reconciliationRacesUserConfirmButApprovesOnce() throws Exception {
        var p = prepare(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pg.afterApproval =
                () -> {
                    entered.countDown();
                    await(release);
                };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> confirm(p));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            age(31);
            reconciler.runOnce();
            release.countDown();
            future.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(handler.approved).hasSize(1);
        assertThat(pg.approvals.get()).isEqualTo(1);
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void handlerExceptionRollsBackAndDoesNotBlockNextPayment() {
        var first = prepare(1);
        processing(first);
        var second = prepare(2);
        processing(second);
        pg.results.put(first.pgOrderId(), approved(first));
        pg.results.put(second.pgOrderId(), approved(second));
        pg.duringQuery = () -> handler.throwApproved = pg.queries.get() == 1;
        age(31);
        double before = failure("apply");
        reconciler.runOnce();
        assertThat(payment(first).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment(second).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='approved'",
                                Long.class))
                .isEqualTo(1);
        assertThat(failure("apply")).isEqualTo(before + 1);
    }

    @Test
    void schedulerDisabledLeavesManualReconcilerAvailable() {
        assertThat(context.getBeansOfType(PaymentReconciliationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(PaymentSchedulingConfig.class)).isEmpty();
        assertThat(reconciler).isNotNull();
    }

    @Test
    void ambiguousAndAnomalousResultsNeverFail() {
        for (var result :
                List.of(
                        new PgQueryResult.Unknown("timeout"),
                        new PgQueryResult.NotFound(),
                        new PgQueryResult.Cancelled("tx", NOW))) {
            var p = prepare(payments.count() + 1);
            processing(p);
            pg.results.put(p.pgOrderId(), result);
            age(31);
            reconciler.runOnce();
            assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        }
        assertThat(handler.failed).isEmpty();
    }

    @Test
    void inProgressDoesNotTransitionAndAmountMismatchProtectsOnce() {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), new PgQueryResult.InProgress("IN_PROGRESS"));
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        pg.results.put(p.pgOrderId(), new PgQueryResult.Approved("key", p.pgOrderId(), 1, NOW));
        age(62);
        reconciler.runOnce();
        age(73);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(handler.unknown).hasSize(1);
        assertThat(handler.approved).isEmpty();
    }

    @Test
    void unknownAgeDoesNotResetOnRepeatedQueries() {
        var p = prepare(1);
        fake.willTimeout(p.pgOrderId());
        confirm(p);
        age(301);
        reconciler.runOnce();
        assertThat(meters.get("payment.unknown.count").gauge().value()).isEqualTo(1);
        assertThat(meters.get("payment.unknown.oldest.age.seconds").gauge().value()).isEqualTo(301);
        age(400);
        reconciler.runOnce();
        assertThat(meters.get("payment.unknown.oldest.age.seconds").gauge().value()).isEqualTo(400);
        assertThat(payment(p).getUnknownSinceAt()).isEqualTo(NOW);
        assertThat(payment(p).getUpdatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void twoRunsCompleteCancellationOnce() throws Exception {
        var p = prepare(1);
        cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, true);
        pg.results.put(p.pgOrderId(), new PgQueryResult.Cancelled("cancel-tx", NOW));
        age(31);
        var barrier = new CyclicBarrier(2);
        pg.duringQuery = () -> await(barrier);
        concurrent(reconciler::runOnce, reconciler::runOnce);
        assertThat(handler.refunded)
                .containsExactly(
                        new RefundContext(p.paymentId(), PaymentRefundKind.CUSTOMER_REFUND));
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void twoRunsExpireReadyOnce() throws Exception {
        var p = prepare(1);
        age(301);
        concurrent(reconciler::runOnce, reconciler::runOnce);
        assertThat(handler.failed).containsExactly(PaymentAttemptContext.notStarted(p.paymentId()));
        assertThat(pg.queries.get()).isZero();
    }

    @Test
    void unknownRetryIntervalIsBasedOnLastQuery() {
        var p = prepare(1);
        fake.willTimeout(p.pgOrderId());
        confirm(p);
        age(9);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isZero();
        age(10);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(1);
        age(19);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(1);
        age(20);
        reconciler.runOnce();
        assertThat(pg.queries.get()).isEqualTo(2);
        assertThat(handler.unknown).hasSize(1);
    }

    @Test
    void processingQuerySuspendsCallingTransaction() {
        var p = prepare(1);
        processing(p);
        pg.results.put(p.pgOrderId(), approved(p));
        age(31);
        new TransactionTemplate(tm).executeWithoutResult(s -> reconciler.runOnce());
        assertThat(pg.txSeen).isFalse();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void batchLimitSelectsOldestFirst() {
        var recent = prepare(1);
        processing(recent);
        var middle = prepare(2);
        change(middle, x -> x.startProcessing("middle", NOW.minusSeconds(5)));
        var oldest = prepare(3);
        change(oldest, x -> x.startProcessing("oldest", NOW.minusSeconds(10)));
        for (var p : List.of(recent, middle, oldest)) pg.results.put(p.pgOrderId(), approved(p));
        age(31);
        var limited =
                new PaymentReconciler(
                        payments,
                        transactions,
                        pg,
                        clock,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new PaymentReconciliationProperties(
                                2,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(30),
                                Duration.ofMinutes(15)));
        limited.runOnce();
        assertThat(handler.approved)
                .extracting(ApprovedPayment::paymentId)
                .containsExactly(oldest.paymentId(), middle.paymentId());
        assertThat(payment(recent).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(pg.queries.get()).isEqualTo(2);
        limited.runOnce();
        assertThat(payment(recent).getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void missingRefundIsAnomalyWithoutPg() {
        var p = prepare(1);
        confirm(p);
        change(p, x -> x.requestCancel(NOW));
        age(31);
        var counter = meters.counter("payment.reconcile.anomaly", "reason", "missing_refund");
        double before = counter.count();
        reconciler.runOnce();
        assertThat(counter.count()).isEqualTo(before + 1);
        assertThat(pg.queries.get()).isZero();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(handler.refunded).isEmpty();
    }

    @Test
    void cancellationUnknownCanBeRetriedRepeatedlyWithoutInvalidSelfTransition() {
        var p = prepare(1);
        var refund = cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, true);
        pg.cancelMode = "UNKNOWN";
        age(31);
        reconciler.runOnce();
        age(42);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_UNKNOWN);
        assertThat(refunds.findById(refund.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentRefundStatus.UNKNOWN);
        assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(refund.getIdempotencyKey());
        assertThat(pg.cancels.get()).isEqualTo(2);
        pg.cancelMode = "COMPLETED";
        age(53);
        reconciler.runOnce();
        assertThat(handler.refunded)
                .containsExactly(
                        new RefundContext(p.paymentId(), PaymentRefundKind.CUSTOMER_REFUND));
    }

    @Test
    void cancellationRejectedRevertsApprovalWithoutRefundCallback() {
        var p = prepare(1);
        var refund = cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, false);
        pg.cancelMode = "REJECTED";
        age(31);
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(refunds.findById(refund.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentRefundStatus.FAILED);
        assertThat(handler.refunded).isEmpty();
    }

    @Test
    void unresolvedCancellationQueryNeverCompletesOrReverts() {
        var p = prepare(1);
        var refund = cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, true);
        for (var result :
                List.of(
                        new PgQueryResult.NotFound(),
                        new PgQueryResult.InProgress("IN_PROGRESS"),
                        new PgQueryResult.Unknown("timeout"),
                        new PgQueryResult.NotApproved("ABORTED", "code", "message"))) {
            age(31 + pg.queries.get() * 11L);
            pg.results.put(p.pgOrderId(), result);
            reconciler.runOnce();
            assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_UNKNOWN);
            assertThat(refunds.findById(refund.getId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentRefundStatus.UNKNOWN);
        }
        assertThat(pg.cancels.get()).isZero();
        assertThat(handler.refunded).isEmpty();
    }

    @Test
    void cancelledCallbackFailureRollsBackAndIsRecoveredOnNextRun() {
        var p = prepare(1);
        var refund = cancellation(p, PaymentRefundKind.CUSTOMER_REFUND, true);
        pg.results.put(p.pgOrderId(), new PgQueryResult.Cancelled("cancel-tx", NOW));
        handler.throwRefunded = true;
        age(31);
        double before = failure("cancel_apply");
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_UNKNOWN);
        assertThat(refunds.findById(refund.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentRefundStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isZero();
        assertThat(failure("cancel_apply")).isEqualTo(before + 1);
        handler.throwRefunded = false;
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void queryExceptionProtectsPaymentAndContinuesNext() {
        var p = prepare(1);
        processing(p);
        var next = prepare(2);
        processing(next);
        pg.results.put(next.pgOrderId(), approved(next));
        age(31);
        pg.duringQuery =
                () -> {
                    if (pg.queries.get() == 1) throw new IllegalStateException("transport failed");
                };
        double before = failure("query");
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment(next).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(failure("query")).isEqualTo(before + 1);
        assertThat(handler.unknown).containsExactly(new PaymentAttemptContext(p.paymentId(), NOW));
    }

    @Test
    void initialAndRetriedCompensationUseIdenticalStoredReasonAndKey() {
        var p = prepare(1);
        fake.willLoseResponse(p.pgOrderId());
        confirm(p);
        handler.approvedDecision = ApprovalDecision.reject("r".repeat(350));
        pg.cancelMode = "UNKNOWN";
        age(11);
        reconciler.runOnce();
        var first = pg.lastCancel;
        var stored = refunds.findAll().getFirst();
        assertThat(first.reason()).isEqualTo(stored.getReason()).hasSize(200);
        assertThat(first.idempotencyKey()).isEqualTo(stored.getIdempotencyKey());
        age(22);
        reconciler.runOnce();
        assertThat(pg.lastCancel).isEqualTo(first);
        assertThat(pg.cancels.get()).isEqualTo(2);
    }

    double failure(String stage) {
        var c = meters.find("payment.reconcile.failure").tag("stage", stage).counter();
        return c == null ? 0 : c.count();
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    void concurrent(Runnable a, Runnable b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f = pool.submit(a);
            var g = pool.submit(b);
            f.get(20, TimeUnit.SECONDS);
            g.get(20, TimeUnit.SECONDS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean("dropPaymentResultHandler")
        PaymentFlowTest.Handler handler(JdbcTemplate jdbc) {
            return new PaymentFlowTest.Handler(jdbc);
        }

        @Bean
        @Primary
        MutableClock paymentClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        ObservedPg observedPg(FakePgClient fake) {
            return new ObservedPg(fake);
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = NOW;

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    static class ObservedPg extends PaymentFlowTest.ObservedPg {
        final Map<String, PgQueryResult> results = new ConcurrentHashMap<>();
        final AtomicInteger queries = new AtomicInteger();
        volatile Runnable duringQuery = () -> {}, afterApproval = () -> {};
        volatile PgCancelCommand lastCancel;

        ObservedPg(FakePgClient fake) {
            super(fake);
        }

        @Override
        void reset() {
            super.reset();
            results.clear();
            queries.set(0);
            duringQuery = () -> {};
            afterApproval = () -> {};
            lastCancel = null;
        }

        @Override
        public PgQueryResult query(String id) {
            txSeen |= TransactionSynchronizationManager.isActualTransactionActive();
            queries.incrementAndGet();
            duringQuery.run();
            return results.containsKey(id) ? results.get(id) : fake.query(id);
        }

        @Override
        public PgApprovalResult approve(PgApproveCommand c) {
            var r = super.approve(c);
            afterApproval.run();
            return r;
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand c) {
            lastCancel = c;
            return super.cancel(c);
        }
    }
}
