package com.restless.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.common.error.*;
import com.restless.payment.application.*;
import com.restless.payment.application.payable.*;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.*;
import com.restless.payment.infrastructure.fake.FakePgClient;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentCustomerRefundTest.Config.class})
class PaymentCustomerRefundTest {
    static final Instant NOW = PaymentFlowTest.NOW;
    @Autowired PaymentService paymentsService;
    @Autowired PaymentRefundService service;
    @Autowired PaymentReconciler reconciler;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired Handler handler;
    @Autowired Pg pg;
    @Autowired FakePgClient fake;
    @Autowired PaymentReconciliationTest.MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @Autowired MockMvc mvc;
    @Autowired com.restless.auth.infrastructure.JwtProperties jwtProperties;
    JwtTokenProvider tokens;
    @Autowired MeterRegistry meters;
    Long user;

    @BeforeEach
    void setup() {
        tokens = new JwtTokenProvider(jwtProperties, Clock.systemUTC());
        refunds.deleteAllInBatch();
        payments.deleteAllInBatch();
        clock.now = NOW;
        jdbc.update(
                "INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)"
                        + " VALUES (?,'hash','refund','USER','ACTIVE',?,?)",
                UUID.randomUUID() + "@refund.test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        user = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS test_payment_handler_effects (payment_id BIGINT NOT"
                        + " NULL, stage VARCHAR(20) NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS test_refund_handler_effects (payment_id BIGINT NOT"
                        + " NULL, stage VARCHAR(20) NOT NULL)");
        jdbc.update("DELETE FROM test_payment_handler_effects");
        jdbc.update("DELETE FROM test_refund_handler_effects");
        handler.reset(new PayableSnapshot(user, 39000, "item", NOW.plusSeconds(300)));
        pg.reset();
        fake.reset();
    }

    PreparePaymentResult ready(long target) {
        return paymentsService.prepare(
                new PreparePaymentCommand(
                        user, PayableType.DROP, target, UUID.randomUUID().toString()));
    }

    PreparePaymentResult approved(long target) {
        var p = ready(target);
        paymentsService.confirm(
                new ConfirmPaymentCommand(user, "key-" + p.paymentId(), p.pgOrderId(), 39000));
        return p;
    }

    RefundPaymentResult refund(PreparePaymentResult p, String key) {
        return service.refund(new RefundPaymentCommand(user, p.paymentId(), key, "단순 변심"));
    }

    Payment payment(PreparePaymentResult p) {
        return payments.findById(p.paymentId()).orElseThrow();
    }

    PaymentRefund row() {
        return refunds.findAll().getLast();
    }

    void error(Runnable task, ErrorCode code) {
        assertThatThrownBy(task::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    double count(String name, String result) {
        var c = meters.find(name).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void approvedRefundCommitsFullAmountAndCallbacksInOrder() {
        var p = approved(1);
        double before = count("payment.refund", "completed");
        var result = refund(p, "one");
        assertThat(result.refundId()).isEqualTo(row().getId());
        assertThat(result.status()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(row().getKind()).isEqualTo(PaymentRefundKind.CUSTOMER_REFUND);
        assertThat(row().getAmount()).isEqualTo(39000);
        assertThat(row().getCancelTransactionKey()).isNotBlank();
        assertThat(row().getCompletedAt()).isEqualTo(NOW);
        assertThat(handler.events).containsExactly("requesting", "refunded");
        assertThat(handler.refunded)
                .containsExactly(
                        new RefundContext(p.paymentId(), PaymentRefundKind.CUSTOMER_REFUND));
        assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(row().getIdempotencyKey());
        assertThat(pg.lastCancel.reason()).isEqualTo("단순 변심");
        assertThat(pg.txSeen).isFalse();
        assertThat(count("payment.refund", "completed")).isEqualTo(before + 1);
        assertThat(meters.get("payment.pg.cancel").tag("kind", "CUSTOMER_REFUND").timer().count())
                .isPositive();
    }

    @Test
    void requestingHandlerRejectsWithoutPgOrRefundRow() {
        var p = approved(1);
        handler.refundDecision = ApprovalDecision.reject("AFTER_CONCERT");
        double before = count("payment.refund.request", "not_refundable");
        assertThatThrownBy(() -> refund(p, "one"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.PAYMENT_NOT_REFUNDABLE);
                            assertThat(e.getDetails()).containsEntry("reason", "AFTER_CONCERT");
                        });
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(refunds.count()).isZero();
        assertThat(pg.cancels.get()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_refund_handler_effects", Long.class))
                .isZero();
        assertThat(count("payment.refund.request", "not_refundable")).isEqualTo(before + 1);
    }

    @Test
    void defaultHandlerMethodRejectsUnsupportedRefund() {
        var p = approved(1);
        handler.useDefault = true;
        assertThatThrownBy(() -> refund(p, "one"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getDetails())
                                        .containsEntry("reason", "REFUND_NOT_SUPPORTED"));
        assertThat(refunds.count()).isZero();
        assertThat(pg.cancels.get()).isZero();
    }

    @Test
    void finalPgRejectionCallsRejectedAndAllowsNewKey() {
        var p = approved(1);
        pg.cancelMode = "REJECTED";
        assertThatThrownBy(() -> refund(p, "one"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.PAYMENT_REFUND_REJECTED);
                            assertThat(e.getDetails()).containsEntry("code", "CANCEL_REJECTED");
                        });
        var failed = row();
        assertThat(failed.getStatus()).isEqualTo(PaymentRefundStatus.FAILED);
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(handler.events).containsExactly("requesting", "rejected");
        assertThat(handler.refunded).isEmpty();
        error(() -> refund(p, "one"), ErrorCode.PAYMENT_REFUND_REJECTED);
        assertThat(pg.cancels.get()).isEqualTo(1);
        pg.cancelMode = "COMPLETED";
        assertThat(refund(p, "two").status()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(refunds.count()).isEqualTo(2);
    }

    @Test
    void unknownIs202AndReconcilerCompletesStoredCustomerRefund() throws Exception {
        var p = approved(1);
        pg.cancelMode = "UNKNOWN";
        mvc.perform(
                        post(url(p))
                                .header("Authorization", auth(user))
                                .header("Idempotency-Key", "one")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"단순 변심\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_UNKNOWN);
        assertThat(row().getStatus()).isEqualTo(PaymentRefundStatus.UNKNOWN);
        assertThat(handler.refunded).isEmpty();
        pg.cancelMode = "COMPLETED";
        clock.now = NOW.plusSeconds(11);
        reconciler.runOnce();
        reconciler.runOnce();
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(row().getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
        assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(row().getIdempotencyKey());
        assertThat(handler.refunded).hasSize(1);
        assertThat(handler.refunded.getFirst().kind()).isEqualTo(PaymentRefundKind.CUSTOMER_REFUND);
    }

    @Test
    void pendingRefundReconcilesSameKeyAndCallsRejected() {
        var p = approved(1);
        handler.throwRefunded = true;
        assertThat(refund(p, "one").status()).isEqualTo(PaymentRefundStatus.REQUESTED);
        // PG was cancelled but TX-R2 rolled back. Query override simulates a still-approved pending
        // cancel for this recovery branch.
        pg.results.put(
                p.pgOrderId(),
                new PgQueryResult.Approved("key-" + p.paymentId(), p.pgOrderId(), 39000, NOW));
        handler.throwRefunded = false;
        pg.cancelMode = "REJECTED";
        clock.now = NOW.plusSeconds(31);
        reconciler.runOnce();
        assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(row().getIdempotencyKey());
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(row().getStatus()).isEqualTo(PaymentRefundStatus.FAILED);
        assertThat(handler.rejected)
                .containsExactly(
                        new RefundContext(p.paymentId(), PaymentRefundKind.CUSTOMER_REFUND));
    }

    @Test
    void sameKeyReplaysSameRefundWithoutPg() {
        var p = approved(1);
        double requested = count("payment.refund", "requested");
        double completed = count("payment.refund", "completed");
        var first = refund(p, "one");
        var second = refund(p, "one");
        assertThat(second).isEqualTo(first);
        assertThat(count("payment.refund", "requested")).isEqualTo(requested + 1);
        assertThat(count("payment.refund", "completed")).isEqualTo(completed + 1);
        assertThat(pg.cancels.get()).isEqualTo(1);
        assertThat(handler.events).containsExactly("requesting", "refunded");
    }

    @Test
    void sameKeyWhileInitialPgIsBlockedReturnsRequestedWithoutAnotherCall() throws Exception {
        var p = approved(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pg.duringCancel =
                () -> {
                    entered.countDown();
                    await(release);
                };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> refund(p, "one"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var replay = refund(p, "one");
                assertThat(replay.status()).isEqualTo(PaymentRefundStatus.REQUESTED);
                release.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS).refundId()).isEqualTo(replay.refundId());
            } finally {
                release.countDown();
            }
        }
        assertThat(pg.cancels.get()).isEqualTo(1);
    }

    @Test
    void tenDifferentKeysHaveOneRefundAndOnePgCallNo500() throws Exception {
        var p = approved(1);
        var codes =
                parallel(
                        10,
                        i -> {
                            try {
                                refund(p, "key-" + i);
                                return 200;
                            } catch (BusinessException e) {
                                return e.getErrorCode().getHttpStatus().value();
                            }
                        });
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 409);
        assertThat(refunds.count()).isEqualTo(1);
        assertThat(pg.cancels.get()).isEqualTo(1);
        assertThat(handler.events).containsExactly("requesting", "refunded");
    }

    @Test
    void tenSameKeysHaveSameIdAndOnePgCall() throws Exception {
        var p = approved(1);
        var results = parallel(10, i -> refund(p, "same"));
        assertThat(results).extracting(RefundPaymentResult::refundId).containsOnly(row().getId());
        assertThat(pg.cancels.get()).isEqualTo(1);
    }

    @Test
    void sameClientKeyAcrossPaymentsIsIndependentEvenConcurrently() throws Exception {
        var p = approved(1);
        var other = approved(2);
        var results = parallel(2, i -> refund(i == 0 ? p : other, "shared"));
        assertThat(results)
                .extracting(RefundPaymentResult::status)
                .containsOnly(PaymentRefundStatus.COMPLETED);
        assertThat(results).extracting(RefundPaymentResult::refundId).doesNotHaveDuplicates();
        assertThat(refunds.count()).isEqualTo(2);
        assertThat(pg.cancels.get()).isEqualTo(2);
        assertThat(refunds.findAll())
                .extracting(PaymentRefund::getIdempotencyKey)
                .doesNotHaveDuplicates()
                .doesNotContain("shared");
    }

    @Test
    void anotherOwnersClientKeyAndCompensationFormatDoNotConflict() {
        var p = approved(1);
        var rejected = ready(3);
        var collision = "compensation-" + rejected.paymentId();
        refund(p, collision);
        var other = approved(2);
        jdbc.update(
                "INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)"
                    + " VALUES (?,'hash','other','USER','ACTIVE',?,?)",
                UUID.randomUUID() + "@refund.test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        Long otherUser = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbc.update("UPDATE payments SET user_id=? WHERE id=?", otherUser, other.paymentId());
        assertThat(
                        service.refund(
                                        new RefundPaymentCommand(
                                                otherUser, other.paymentId(), collision, "reason"))
                                .status())
                .isEqualTo(PaymentRefundStatus.COMPLETED);
        // A real compensation is still able to reserve the exact key the customer supplied.
        handler.approvedDecision = ApprovalDecision.reject("cannot fulfill");
        error(
                () ->
                        paymentsService.confirm(
                                new ConfirmPaymentCommand(
                                        user, "comp-key", rejected.pgOrderId(), 39000)),
                ErrorCode.PAYMENT_REJECTED);
        assertThat(refunds.findAll())
                .filteredOn(r -> r.getKind() == PaymentRefundKind.CUSTOMER_REFUND)
                .allSatisfy(r -> assertThat(r.getIdempotencyKey()).startsWith("refund-"));
    }

    @Test
    void statesAndOwnershipAreChecked() {
        var p = ready(1);
        error(() -> refund(p, "one"), ErrorCode.PAYMENT_NOT_REFUNDABLE);
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        s ->
                                payments.findByIdForUpdate(p.paymentId())
                                        .orElseThrow()
                                        .fail("FAIL", "failed", NOW));
        error(() -> refund(p, "one"), ErrorCode.PAYMENT_NOT_REFUNDABLE);
        var paid = approved(2);
        refund(paid, "used");
        error(() -> refund(paid, "new"), ErrorCode.PAYMENT_ALREADY_REFUNDED);
        var other = approved(3);
        error(
                () ->
                        service.refund(
                                new RefundPaymentCommand(
                                        user + 1, paid.paymentId(), "used", "reason")),
                ErrorCode.PAYMENT_NOT_FOUND);
        error(
                () ->
                        service.refund(
                                new RefundPaymentCommand(user, Long.MAX_VALUE, "used", "reason")),
                ErrorCode.PAYMENT_NOT_FOUND);
        pg.cancelMode = "UNKNOWN";
        refund(other, "pending");
        error(() -> refund(other, "new"), ErrorCode.PAYMENT_REFUND_IN_PROGRESS);
    }

    @Test
    void resultHandlerFailureReturns202PendingAndReconcilesAtomicEffect() throws Exception {
        var p = approved(1);
        handler.throwRefunded = true;
        double before = meters.counter("payment.refund.apply.failure").count();
        mvc.perform(
                        post(url(p))
                                .header("Authorization", auth(user))
                                .header("Idempotency-Key", "one")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"reason\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(row().getStatus()).isEqualTo(PaymentRefundStatus.REQUESTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                        + " stage='refunded'",
                                Long.class))
                .isZero();
        assertThat(meters.counter("payment.refund.apply.failure").count()).isEqualTo(before + 1);
        handler.throwRefunded = false;
        clock.now = NOW.plusSeconds(31);
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
    void rejectedCallbackFailureAlsoRemainsPending() {
        var p = approved(1);
        pg.cancelMode = "REJECTED";
        handler.throwRejected = true;
        assertThat(refund(p, "one").status()).isEqualTo(PaymentRefundStatus.REQUESTED);
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(row().getStatus()).isEqualTo(PaymentRefundStatus.REQUESTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_refund_handler_effects WHERE"
                                        + " stage='rejected'",
                                Long.class))
                .isZero();
    }

    @Test
    void pgExceptionIsUnknownAndOutsideCallerTransaction() {
        var p = approved(1);
        pg.throwCancel = true;
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        s ->
                                assertThat(refund(p, "one").status())
                                        .isEqualTo(PaymentRefundStatus.UNKNOWN));
        assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.CANCEL_UNKNOWN);
        assertThat(pg.txSeen).isFalse();
        assertThat(handler.refunded).isEmpty();
        assertThat(pg.cancels.get()).isEqualTo(1);
    }

    @Test
    void zeroAmountCannotReachApprovedPgPayment() {
        handler.snapshot = new PayableSnapshot(user, 0, "free", null);
        error(() -> ready(1), ErrorCode.PAYMENT_NOT_PAYABLE);
        assertThat(payments.count()).isZero();
        assertThat(refunds.count()).isZero();
    }

    @Test
    void compensationNeverCallsCustomerRequestingOrRejectedCallbacks() {
        var p = ready(1);
        handler.approvedDecision = ApprovalDecision.reject("cannot fulfill");
        pg.cancelMode = "REJECTED";
        error(
                () ->
                        paymentsService.confirm(
                                new ConfirmPaymentCommand(user, "key", p.pgOrderId(), 39000)),
                ErrorCode.PAYMENT_REJECTED);
        assertThat(handler.events).isEmpty();
        assertThat(handler.rejected).isEmpty();
        assertThat(row().getKind()).isEqualTo(PaymentRefundKind.COMPENSATION);
        assertThat(meters.get("payment.pg.cancel").tag("kind", "COMPENSATION").timer().count())
                .isPositive();
    }

    @Test
    void apiValidationAndOwnership() throws Exception {
        var p = approved(1);
        mvc.perform(
                        post(url(p))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"r\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(url(p))
                                .header("Authorization", auth(user + 1))
                                .header("Idempotency-Key", "one")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"r\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post(url(p))
                                .header("Authorization", auth(user))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest());
        for (String key : List.of(" ", "k".repeat(65)))
            mvc.perform(
                            post(url(p))
                                    .header("Authorization", auth(user))
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"r\"}"))
                    .andExpect(status().isBadRequest());
        for (String body :
                List.of(
                        "{}",
                        "{\"reason\":null}",
                        "{\"reason\":\" \"}",
                        "{\"reason\":\"" + "r".repeat(201) + "\"}"))
            mvc.perform(
                            post(url(p))
                                    .header("Authorization", auth(user))
                                    .header("Idempotency-Key", "one")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        assertThat(pg.cancels.get()).isZero();
    }

    @Test
    void apiCompleteReplayAndLatestRefundSummary() throws Exception {
        var p = approved(1);
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", auth(user)))
                .andExpect(jsonPath("$.refund").value(org.hamcrest.Matchers.nullValue()));
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post(url(p))
                                    .header("Authorization", auth(user))
                                    .header("Idempotency-Key", "one")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"reason\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.refundId").value(row().getId()))
                .andExpect(jsonPath("$.refund.kind").value("CUSTOMER_REFUND"))
                .andExpect(jsonPath("$.refund.status").value("COMPLETED"))
                .andExpect(jsonPath("$.refund.requestedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.refund.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.paymentKey").doesNotExist());
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", auth(user + 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delayedInitialRequestNeverSendsANewerRefundsCancellation() throws Exception {
        var p = approved(1);
        var firstCommitted = new CountDownLatch(1);
        var secondCommitted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        handler.afterRequestCommit =
                () -> {
                    if (commits.incrementAndGet() == 1) {
                        firstCommitted.countDown();
                        await(releaseFirst);
                    } else {
                        secondCommitted.countDown();
                        await(releaseSecond);
                    }
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first =
                        pool.submit(
                                () -> {
                                    try {
                                        refund(p, "old");
                                        return "OK";
                                    } catch (BusinessException error) {
                                        return error.getErrorCode().name();
                                    }
                                });
                assertThat(firstCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                pg.cancelMode = "REJECTED";
                clock.now = NOW.plusSeconds(31);
                reconciler.runOnce();
                assertThat(payment(p).getStatus()).isEqualTo(PaymentStatus.APPROVED);
                pg.cancelMode = "COMPLETED";
                var second = pool.submit(() -> refund(p, "new"));
                assertThat(secondCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                releaseFirst.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo("PAYMENT_REFUND_REJECTED");
                assertThat(pg.cancels.get()).isEqualTo(1);
                releaseSecond.countDown();
                assertThat(second.get(15, TimeUnit.SECONDS).status())
                        .isEqualTo(PaymentRefundStatus.COMPLETED);
                assertThat(pg.cancels.get()).isEqualTo(2);
                assertThat(pg.lastCancel.idempotencyKey()).isEqualTo(row().getIdempotencyKey());
                assertThat(handler.refunded).hasSize(1);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    @Test
    void unknownAndReconciledCompletionCountOncePerCommittedTransition() {
        var p = approved(1);
        double requested = count("payment.refund", "requested"),
                unknown = count("payment.refund", "unknown"),
                completed = count("payment.refund", "completed");
        pg.cancelMode = "UNKNOWN";
        refund(p, "metric");
        refund(p, "metric");
        assertThat(count("payment.refund", "requested")).isEqualTo(requested + 1);
        assertThat(count("payment.refund", "unknown")).isEqualTo(unknown + 1);
        clock.now = NOW.plusSeconds(11);
        reconciler.runOnce();
        assertThat(count("payment.refund", "unknown")).isEqualTo(unknown + 1);
        pg.cancelMode = "COMPLETED";
        clock.now = NOW.plusSeconds(22);
        reconciler.runOnce();
        refund(p, "metric");
        assertThat(count("payment.refund", "completed")).isEqualTo(completed + 1);
        assertThat(count("payment.refund", "requested")).isEqualTo(requested + 1);
    }

    @Test
    void rolledBackCompletionAndRejectedReplaysDoNotInflateCounts() {
        var p = approved(1);
        double completed = count("payment.refund", "completed"),
                rejected = count("payment.refund", "rejected");
        handler.throwRefunded = true;
        refund(p, "rollback");
        assertThat(count("payment.refund", "completed")).isEqualTo(completed);
        handler.throwRefunded = false;
        clock.now = NOW.plusSeconds(31);
        reconciler.runOnce();
        assertThat(count("payment.refund", "completed")).isEqualTo(completed + 1);
        var other = approved(2);
        pg.cancelMode = "REJECTED";
        error(() -> refund(other, "reject"), ErrorCode.PAYMENT_REFUND_REJECTED);
        error(() -> refund(other, "reject"), ErrorCode.PAYMENT_REFUND_REJECTED);
        assertThat(count("payment.refund", "rejected")).isEqualTo(rejected + 1);
    }

    String url(PreparePaymentResult p) {
        return "/api/payments/" + p.paymentId() + "/refunds";
    }

    String auth(Long id) {
        return "Bearer " + tokens.issue(id, "USER").value();
    }

    static void await(CountDownLatch latch) {
        PaymentReconciliationTest.await(latch);
    }

    <T> List<T> parallel(int n, java.util.function.IntFunction<T> work) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                jobs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return work.apply(index);
                                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var j : jobs) results.add(j.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean("dropPaymentResultHandler")
        Handler handler(JdbcTemplate jdbc) {
            return new Handler(jdbc);
        }

        @Bean
        @Primary
        PaymentReconciliationTest.MutableClock refundClock() {
            return new PaymentReconciliationTest.MutableClock();
        }

        @Bean
        @Primary
        Pg observedPg(FakePgClient fake) {
            return new Pg(fake);
        }
    }

    static class Handler extends PaymentFlowTest.Handler {
        volatile Runnable afterRequestCommit = () -> {};
        volatile ApprovalDecision refundDecision = ApprovalDecision.accept();
        volatile boolean useDefault, throwRejected;
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<RefundContext> rejected = new CopyOnWriteArrayList<>();

        Handler(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        void reset(PayableSnapshot s) {
            super.reset(s);
            afterRequestCommit = () -> {};
            refundDecision = ApprovalDecision.accept();
            useDefault = false;
            throwRejected = false;
            events.clear();
            rejected.clear();
        }

        public ApprovalDecision onRefundRequesting(Long id, RefundContext r) {
            inTx();
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support
                                    .TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    afterRequestCommit.run();
                                }
                            });
            events.add("requesting");
            jdbc.update(
                    "INSERT INTO test_refund_handler_effects VALUES (?, 'requesting')",
                    r.paymentId());
            return useDefault ? super.onRefundRequesting(id, r) : refundDecision;
        }

        public void onRefundRejected(Long id, RefundContext r) {
            inTx();
            events.add("rejected");
            rejected.add(r);
            jdbc.update(
                    "INSERT INTO test_refund_handler_effects VALUES (?, 'rejected')",
                    r.paymentId());
            if (throwRejected) throw new IllegalStateException("rejected handler failed");
        }

        @Override
        public void onRefunded(Long id, RefundContext r) {
            events.add("refunded");
            super.onRefunded(id, r);
        }
    }

    static class Pg extends PaymentReconciliationTest.ObservedPg {
        volatile boolean throwCancel;

        Pg(FakePgClient fake) {
            super(fake);
        }

        @Override
        void reset() {
            super.reset();
            throwCancel = false;
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand c) {
            if (throwCancel) {
                txSeen |=
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .isActualTransactionActive();
                cancels.incrementAndGet();
                throw new IllegalStateException("transport failure");
            }
            return super.cancel(c);
        }
    }
}
