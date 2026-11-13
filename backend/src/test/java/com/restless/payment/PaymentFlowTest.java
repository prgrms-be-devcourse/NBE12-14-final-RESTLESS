package com.restless.payment;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.auth.infrastructure.JwtProperties;
import com.restless.common.error.*;
import com.restless.payment.application.*;
import com.restless.payment.application.payable.*;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.*;
import com.restless.payment.infrastructure.fake.FakePgClient;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentFlowTest.Config.class})
class PaymentFlowTest {
    static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    @Autowired PaymentService service;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired Handler handler;
    @Autowired ObservedPg pg;
    @Autowired FakePgClient fake;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager tm;
    @Autowired MockMvc mvc;
    @Autowired JwtProperties jwtProperties;
    @Autowired MySQLContainer mysql;
    JwtTokenProvider tokens;
    Long user;

    @BeforeEach
    void setup() {
        // 결제 시나리오는 고정 시계, 실제 JWT 검증을 거치는 API 인증은 현재 시계를 사용한다.
        tokens = new JwtTokenProvider(jwtProperties, Clock.systemUTC());
        refunds.deleteAllInBatch();
        payments.deleteAllInBatch();
        jdbc.update(
                "INSERT INTO users (email,password_hash,nickname,role,status,created_at,updated_at)"
                    + " VALUES (?, 'hash','pay','USER','ACTIVE',?,?)",
                UUID.randomUUID() + "@pay.test",
                java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        user = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS test_payment_handler_effects (payment_id BIGINT NOT"
                    + " NULL, stage VARCHAR(20) NOT NULL)");
        jdbc.update("DELETE FROM test_payment_handler_effects");
        handler.reset(new PayableSnapshot(user, 39000, "후드티 M", NOW.plusSeconds(300)));
        pg.reset();
        fake.reset();
    }

    PreparePaymentResult prepare(String key) {
        return service.prepare(new PreparePaymentCommand(user, PayableType.DROP, 123L, key));
    }

    ConfirmPaymentResult confirm(PreparePaymentResult p) {
        return service.confirm(
                new ConfirmPaymentCommand(
                        user, "payment-key-" + p.paymentId(), p.pgOrderId(), 39000));
    }

    Payment payment(Long id) {
        return payments.findById(id).orElseThrow();
    }

    void change(Long id, Consumer<Payment> action) {
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        s -> action.accept(payments.findByIdForUpdate(id).orElseThrow()));
    }

    void error(Runnable task, ErrorCode code) {
        assertThatThrownBy(task::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void prepareCreatesAndReplaysCurrentAttempt() {
        var p = prepare("one");
        var replay = prepare("one");
        assertThat(p.created()).isTrue();
        assertThat(p.amount()).isEqualTo(39000);
        assertThat(p.orderName()).isEqualTo("후드티 M");
        assertThat(p.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(replay.paymentId()).isEqualTo(p.paymentId());
        assertThat(replay.created()).isFalse();
        confirm(p);
        assertThat(prepare("one").status()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void prepareChecksOwnerMissingTargetAndExpiry() {
        handler.snapshot = new PayableSnapshot(user + 1, 39000, "item", null);
        error(() -> prepare("one"), ErrorCode.PAYMENT_TARGET_NOT_FOUND);
        handler.snapshot = null;
        error(() -> prepare("one"), ErrorCode.PAYMENT_NOT_PAYABLE);
        handler.snapshot = new PayableSnapshot(user, 39000, "item", NOW.minusSeconds(1));
        error(() -> prepare("one"), ErrorCode.PAYMENT_WINDOW_EXPIRED);
    }

    @Test
    void freeOrderIsNotPreparedForPg() {
        handler.snapshot = new PayableSnapshot(user, 0, "무료 응모", NOW.plusSeconds(300));
        assertThatThrownBy(() -> prepare("free"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_PAYABLE);
                            assertThat(e.getDetails()).isEqualTo(Map.of("reason", "FREE_ORDER"));
                        });
        assertThat(payments.count()).isZero();
    }

    @Test
    void supersededReadyCannotMoveMoneyOrCallHandler() {
        var p = prepare("one");
        assertThat(prepare("two").paymentId()).isNotEqualTo(p.paymentId());
        assertThat(payment(p.paymentId()).getFailureCode()).isEqualTo("SUPERSEDED");
        error(() -> confirm(p), ErrorCode.PAYMENT_FAILED);
        assertThat(pg.approvals.get()).isZero();
        assertThat(handler.failed).isEmpty();
        assertThat(prepare("one").status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void processingUnknownAndApprovedBlockNewKeys() {
        var p = prepare("one");
        change(p.paymentId(), x -> x.startProcessing("key", NOW));
        error(() -> prepare("two"), ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
        change(p.paymentId(), x -> x.markUnknown(NOW));
        error(() -> prepare("two"), ErrorCode.PAYMENT_UNDER_REVIEW);
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(pg.approvals.get()).isZero();
        change(p.paymentId(), x -> x.approve(NOW, NOW));
        error(() -> prepare("two"), ErrorCode.PAYMENT_NOT_PAYABLE);
    }

    @Test
    void twentyPreparationsKeepOneActiveWithout500() throws Exception {
        var results =
                parallel(
                        20,
                        i -> {
                            try {
                                prepare("key-" + i);
                                return 201;
                            } catch (BusinessException e) {
                                return e.getErrorCode().getHttpStatus().value();
                            }
                        });
        assertThat(results).allMatch(x -> x == 201 || x == 409);
        assertThat(
                        payments.findAll().stream()
                                .filter(
                                        x ->
                                                x.getStatus() != PaymentStatus.FAILED
                                                        && x.getStatus()
                                                                != PaymentStatus.CANCELLED))
                .hasSize(1);
    }

    @Test
    void inv11_tenConfirmationsApproveOnceOutsideTransaction() throws Exception {
        var p = prepare("one");
        fake.willDelay(p.pgOrderId(), Duration.ofMillis(300));
        var results = parallel(10, i -> confirm(p).status());
        assertThat(results)
                .allMatch(x -> x == PaymentStatus.APPROVED || x == PaymentStatus.PROCESSING);
        assertThat(pg.approvals.get()).isEqualTo(1);
        assertThat(handler.approved).hasSize(1);
        assertThat(pg.txSeen).isFalse();
        assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        var received = handler.approved.getFirst();
        assertThat(received.paymentId()).isEqualTo(p.paymentId());
        assertThat(received.approvalStartedAt()).isEqualTo(NOW);
        assertThat(received.approvedAt()).isEqualTo(NOW);
        assertThat(pg.lastApprove.idempotencyKey()).isEqualTo("approve-" + p.paymentId());
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(pg.approvals.get()).isEqualTo(1);
    }

    @Test
    void inv08_amountMismatchLeavesReadyWithoutPg() {
        var p = prepare("one");
        error(
                () -> service.confirm(new ConfirmPaymentCommand(user, "key", p.pgOrderId(), 1)),
                ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(pg.approvals.get()).isZero();
        assertThat(handler.starting).isEmpty();
    }

    @Test
    void expiredConfirmCommitsFailureAndNotStartedContext() {
        var p = prepare("one");
        jdbc.update(
                "UPDATE payments SET expires_at=? WHERE id=?",
                LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
                p.paymentId());
        assertThat(payment(p.paymentId()).getExpiresAt()).isEqualTo(NOW.minusSeconds(1));
        error(() -> confirm(p), ErrorCode.PAYMENT_WINDOW_EXPIRED);
        assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(handler.failed).containsExactly(PaymentAttemptContext.notStarted(p.paymentId()));
        assertThat(pg.approvals.get()).isZero();
    }

    @Test
    void startingRejectCommitsFailureWithoutFailedCallback() {
        var p = prepare("one");
        handler.startDecision = ApprovalDecision.reject("expired reservation");
        error(() -> confirm(p), ErrorCode.PAYMENT_NOT_PAYABLE);
        assertThat(payment(p.paymentId()).getFailureCode()).isEqualTo("APPROVAL_REJECTED");
        assertThat(handler.failed).isEmpty();
        assertThat(pg.approvals.get()).isZero();
    }

    @Test
    void pgRejectedCommitsFailureAndAttemptContext() {
        var p = prepare("one");
        fake.willReject(p.pgOrderId(), "CARD_REJECTED");
        error(() -> confirm(p), ErrorCode.PAYMENT_FAILED);
        assertThat(payment(p.paymentId()).getFailureCode()).isEqualTo("CARD_REJECTED");
        assertThat(handler.failed).containsExactly(new PaymentAttemptContext(p.paymentId(), NOW));
        assertThat(prepare("two").created()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inv10_timeoutAndLostResponseRemainUnknown(boolean lose) {
        var p = prepare("one");
        if (lose) fake.willLoseResponse(p.pgOrderId());
        else fake.willTimeout(p.pgOrderId());
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(handler.unknown).containsExactly(new PaymentAttemptContext(p.paymentId(), NOW));
        assertThat(handler.failed).isEmpty();
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(pg.approvals.get()).isEqualTo(1);
    }

    @Test
    void wrongApprovedAmountIsUnknownAndObservable() {
        var p = prepare("one");
        pg.wrongAmount = true;
        double before = count("payment.amount.mismatch");
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(handler.approved).isEmpty();
        assertThat(handler.unknown).hasSize(1);
        assertThat(count("payment.amount.mismatch")).isEqualTo(before + 1);
    }

    @Test
    void technicalFailureRollsBackResultAndReturnsProcessing() {
        var p = prepare("one");
        handler.throwApproved = true;
        double before = count("payment.result.apply.failure");
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(refunds.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                    + " stage='approved'",
                                Long.class))
                .isZero();
        assertThat(count("payment.result.apply.failure")).isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_code FROM payments WHERE id=?",
                                String.class,
                                p.paymentId()))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "REJECTED", "UNKNOWN"})
    void compensationCommitsPendingBeforePgAndAppliesRefundKind(String result) {
        double rejectedBefore = count("payment.compensation.rejected");
        var p = prepare("one");
        handler.approvedDecision = ApprovalDecision.reject("reservation unavailable");
        pg.cancelMode = result;
        pg.duringCancel =
                () ->
                        assertThat(payment(p.paymentId()).getStatus())
                                .isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThatThrownBy(() -> confirm(p))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_REJECTED);
                            assertThat(e.getDetails())
                                    .containsEntry("reason", "reservation unavailable")
                                    .containsEntry("refund", result);
                        });
        assertThat(count("payment.compensation.rejected"))
                .isEqualTo(rejectedBefore + (result.equals("REJECTED") ? 1 : 0));
        var r = refunds.findAll().getFirst();
        assertThat(r.getKind()).isEqualTo(PaymentRefundKind.COMPENSATION);
        assertThat(r.getIdempotencyKey()).isEqualTo("compensation-" + p.paymentId());
        assertThat(pg.txSeen).isFalse();
        assertThat(pg.cancels.get()).isEqualTo(1);
        if (result.equals("COMPLETED")) {
            assertThat(r.getStatus()).isEqualTo(PaymentRefundStatus.COMPLETED);
            assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(handler.refunded)
                    .containsExactly(
                            new RefundContext(p.paymentId(), PaymentRefundKind.COMPENSATION));
        } else {
            assertThat(r.getStatus())
                    .isEqualTo(
                            result.equals("UNKNOWN")
                                    ? PaymentRefundStatus.UNKNOWN
                                    : PaymentRefundStatus.FAILED);
            assertThat(payment(p.paymentId()).getStatus())
                    .isEqualTo(
                            result.equals("UNKNOWN")
                                    ? PaymentStatus.CANCEL_UNKNOWN
                                    : PaymentStatus.APPROVED);
            assertThat(handler.refunded).isEmpty();
        }
    }

    @Test
    void apiEnforcesAuthenticationHeaderOwnershipAndResponseCodes() throws Exception {
        String body = "{\"payableType\":\"DROP\",\"payableId\":123}";
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String auth = "Bearer " + tokens.issue(user, "USER").value();
        mvc.perform(
                        post("/api/payments")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/payments")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", "api")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().is(i == 0 ? 201 : 200))
                    .andExpect(jsonPath("$.created").value(i == 0));
        var p = prepare("api");
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(39000));
        String other = "Bearer " + tokens.issue(user + 1, "USER").value();
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", other))
                .andExpect(status().isNotFound());
        String confirmation =
                "{\"paymentKey\":\"api-key\",\"orderId\":\""
                        + p.pgOrderId()
                        + "\",\"amount\":39000}";
        mvc.perform(
                        post("/api/payments/confirm")
                                .header("Authorization", other)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(confirmation))
                .andExpect(status().isNotFound());
        fake.willTimeout(p.pgOrderId());
        mvc.perform(
                        post("/api/payments/confirm")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(confirmation))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void sameKeyConcurrentPrepareReturnsOneRowAndSameId() throws Exception {
        var results = parallel(20, i -> prepare("same"));
        assertThat(results.stream().map(PreparePaymentResult::paymentId).distinct()).hasSize(1);
        assertThat(results.stream().filter(PreparePaymentResult::created)).hasSize(1);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void prepareAndApprovalStartOverlapWithoutDeadlockOr500() throws Exception {
        var first = prepare("first");
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        enableLockingHandler(loaded, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var preparation = pool.submit(() -> outcome(() -> prepare("replacement")));
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                var confirmation = pool.submit(() -> outcome(() -> confirm(first)));
                // Old order: confirmation owns P1 and waits for the domain row.
                // Fixed order: preparation owns P1 and confirmation waits for P1.
                // Observe an actual InnoDB wait before allowing preparation to continue.
                awaitPaymentLockWait();
                release.countDown();
                Object prepared = preparation.get(20, TimeUnit.SECONDS);
                Object confirmed = confirmation.get(20, TimeUnit.SECONDS);
                if (prepared instanceof PreparePaymentResult replacement) {
                    assertThat(replacement.created()).isTrue();
                    assertThat(replacement.status()).isEqualTo(PaymentStatus.READY);
                    assertThat(confirmed).isEqualTo(ErrorCode.PAYMENT_FAILED);
                    assertThat(payment(first.paymentId()).getFailureCode()).isEqualTo("SUPERSEDED");
                } else {
                    assertThat(prepared)
                            .isIn(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS, ErrorCode.PAYMENT_NOT_PAYABLE);
                    assertThat(confirmed).isInstanceOfSatisfying(
                            ConfirmPaymentResult.class,
                            result -> assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED));
                }
                assertOneActiveAttempt();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sameKeyCommittedWhilePrepareWaitsIsReplayed() throws Exception {
        prepare("first");
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        enableLockingHandler(loaded, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var first = pool.submit(() -> prepare("same-waiting-key"));
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> prepare("same-waiting-key"));
                awaitPaymentLockWait();
                release.countDown();
                var created = first.get(20, TimeUnit.SECONDS);
                var replay = second.get(20, TimeUnit.SECONDS);
                assertThat(created.created()).isTrue();
                assertThat(replay.created()).isFalse();
                assertThat(replay.paymentId()).isEqualTo(created.paymentId());
                assertThat(replay.status()).isEqualTo(PaymentStatus.READY);
                assertThat(payments.count()).isEqualTo(2);
                assertOneActiveAttempt();
            } finally {
                release.countDown();
            }
        }
    }

    void enableLockingHandler(CountDownLatch loaded, CountDownLatch release) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_payment_lock_rows"
                + " (id BIGINT PRIMARY KEY, touches INT NOT NULL) ENGINE=InnoDB");
        jdbc.update("DELETE FROM test_payment_lock_rows");
        for (int i = 1; i <= 32; i++)
            jdbc.update("INSERT INTO test_payment_lock_rows VALUES (?, 0)", i);
        handler.lockDomainRows = true;
        handler.afterLoadLock = () -> {
            loaded.countDown();
            try {
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
    }

    void awaitPaymentLockWait() throws Exception {
        // Container-local root is used only to observe performance_schema; application SQL
        // still uses the normal datasource and its real transactions/row locks.
        try (var connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l
                          ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA = DATABASE()
                          AND l.OBJECT_NAME IN ('payments', 'test_payment_lock_rows')
                        """)) {
                    rows.next();
                    return rows.getInt(1) > 0;
                }
            });
        }
    }

    Object outcome(Callable<?> request) throws Exception {
        try {
            return request.call();
        } catch (BusinessException e) {
            assertThat(e.getErrorCode().getHttpStatus().value()).isLessThan(500);
            return e.getErrorCode();
        }
    }

    void assertOneActiveAttempt() {
        assertThat(payments.findAll().stream()
                .filter(p -> p.getPayableType() == PayableType.DROP && p.getPayableId() == 123L)
                .filter(p -> p.getStatus() != PaymentStatus.FAILED
                        && p.getStatus() != PaymentStatus.CANCELLED)).hasSize(1);
    }

    @Test
    void storedAttemptOwnerIsCheckedEvenIfSnapshotOwnerChanges() {
        prepare("one");
        handler.snapshot = new PayableSnapshot(user + 1, 39000, "item", null);
        error(
                () ->
                        service.prepare(
                                new PreparePaymentCommand(user + 1, PayableType.DROP, 123L, "one")),
                ErrorCode.PAYMENT_TARGET_NOT_FOUND);
    }

    @Test
    void approvalAtDeadlineCanFinishAfterDeadline() {
        handler.snapshot = new PayableSnapshot(user, 39000, "item", NOW);
        var p = prepare("one");
        pg.approvedAt = NOW.plusSeconds(60);
        assertThat(confirm(p).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(handler.approved.getFirst().approvalStartedAt()).isEqualTo(NOW);
        assertThat(handler.approved.getFirst().approvedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void compensationCallbackFailureRollsBackPaymentRefundAndDomainEffect() {
        var p = prepare("one");
        handler.approvedDecision = ApprovalDecision.reject("cannot fulfill");
        handler.throwRefunded = true;
        double before = count("payment.result.apply.failure");
        assertThatThrownBy(() -> confirm(p))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getDetails()).containsEntry("refund", "UNKNOWN"));
        assertThat(payment(p.paymentId()).getStatus()).isEqualTo(PaymentStatus.CANCEL_PENDING);
        assertThat(refunds.findAll().getFirst().getStatus())
                .isEqualTo(PaymentRefundStatus.REQUESTED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_payment_handler_effects WHERE"
                                    + " stage='refunded'",
                                Long.class))
                .isZero();
        assertThat(count("payment.result.apply.failure")).isEqualTo(before + 1);
    }

    @Test
    void pgCallsRemainOutsideTransactionsEvenWhenCallerHasOne() {
        var p = prepare("one");
        new TransactionTemplate(tm)
                .executeWithoutResult(
                        s -> assertThat(confirm(p).status()).isEqualTo(PaymentStatus.APPROVED));
        assertThat(pg.txSeen).isFalse();
    }

    @Test
    void apiValidationAndTerminalResponses() throws Exception {
        String auth = "Bearer " + tokens.issue(user, "USER").value();
        for (String key : List.of(" ", "k".repeat(65)))
            mvc.perform(
                            post("/api/payments")
                                    .header("Authorization", auth)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"payableType\":\"DROP\",\"payableId\":123}"))
                    .andExpect(status().isBadRequest());
        var p = prepare("one");
        String body =
                "{\"paymentKey\":\"api-key\",\"orderId\":\""
                        + p.pgOrderId()
                        + "\",\"amount\":39000}";
        mvc.perform(get("/api/payments/" + p.paymentId())).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/payments/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/payments/confirm")
                                    .header("Authorization", auth)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/payments/" + p.paymentId()).header("Authorization", auth))
                .andExpect(jsonPath("$.approvalStartedAt").value("2026-11-13T01:00:00Z"))
                .andExpect(jsonPath("$.paymentKey").doesNotExist());
        assertThat(pg.approvals.get()).isEqualTo(1);
    }

    double count(String name) {
        var c = meters.find(name).counter();
        return c == null ? 0 : c.count();
    }

    <T> List<T> parallel(int n, java.util.function.IntFunction<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                fs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return task.apply(index);
                                }));
            }
            start.countDown();
            List<T> vs = new ArrayList<>();
            for (var f : fs) vs.add(f.get(40, TimeUnit.SECONDS));
            return vs;
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
        Clock paymentClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ObservedPg observedPg(FakePgClient fake) {
            return new ObservedPg(fake);
        }
    }

    static class Handler implements PaymentResultHandler {
        volatile PayableSnapshot snapshot;
        volatile ApprovalDecision startDecision, approvedDecision;
        volatile boolean throwApproved, throwRefunded;
        volatile boolean lockDomainRows;
        volatile Runnable afterLoadLock;
        final JdbcTemplate jdbc;

        Handler(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        final List<PaymentAttemptContext> starting = new CopyOnWriteArrayList<>(),
                failed = new CopyOnWriteArrayList<>(),
                unknown = new CopyOnWriteArrayList<>();
        final List<ApprovedPayment> approved = new CopyOnWriteArrayList<>();
        final List<RefundContext> refunded = new CopyOnWriteArrayList<>();

        void reset(PayableSnapshot v) {
            snapshot = v;
            startDecision = ApprovalDecision.accept();
            approvedDecision = ApprovalDecision.accept();
            throwApproved = false;
            throwRefunded = false;
            lockDomainRows = false;
            afterLoadLock = () -> {};
            starting.clear();
            failed.clear();
            unknown.clear();
            approved.clear();
            refunded.clear();
        }

        public PayableType type() {
            return PayableType.DROP;
        }

        public Optional<PayableSnapshot> load(Long id) {
            if (lockDomainRows) {
                jdbc.queryForObject("SELECT id FROM test_payment_lock_rows WHERE id=1 FOR UPDATE", Long.class);
                // Make the preparing transaction heavier so InnoDB consistently chooses
                // approval start as the old-code deadlock victim; prepare's retry cannot hide it.
                jdbc.update("UPDATE test_payment_lock_rows SET touches=touches+1");
                afterLoadLock.run();
            }
            return Optional.ofNullable(snapshot);
        }

        void inTx() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        }

        public ApprovalDecision onApprovalStarting(Long id, PaymentAttemptContext a) {
            inTx();
            if (lockDomainRows)
                jdbc.queryForObject("SELECT id FROM test_payment_lock_rows WHERE id=1 FOR UPDATE", Long.class);
            starting.add(a);
            return startDecision;
        }

        public ApprovalDecision onApproved(Long id, ApprovedPayment p) {
            inTx();
            jdbc.update(
                    "INSERT INTO test_payment_handler_effects VALUES (?, 'approved')",
                    p.paymentId());
            approved.add(p);
            if (throwApproved) throw new IllegalStateException("handler unavailable");
            return approvedDecision;
        }

        public void onFailed(Long id, PaymentAttemptContext a) {
            inTx();
            failed.add(a);
        }

        public void onUnknown(Long id, PaymentAttemptContext a) {
            inTx();
            unknown.add(a);
        }

        public void onRefunded(Long id, RefundContext r) {
            inTx();
            jdbc.update(
                    "INSERT INTO test_payment_handler_effects VALUES (?, 'refunded')",
                    r.paymentId());
            refunded.add(r);
            if (throwRefunded) throw new IllegalStateException("refund handler unavailable");
        }
    }

    static class ObservedPg implements PgClient {
        final FakePgClient fake;
        final AtomicInteger approvals = new AtomicInteger(), cancels = new AtomicInteger();
        volatile boolean txSeen, wrongAmount;
        volatile String cancelMode;
        volatile Runnable duringCancel;
        volatile PgApproveCommand lastApprove;
        volatile Instant approvedAt;

        ObservedPg(FakePgClient fake) {
            this.fake = fake;
        }

        void reset() {
            approvals.set(0);
            cancels.set(0);
            txSeen = false;
            wrongAmount = false;
            approvedAt = null;
            cancelMode = "COMPLETED";
            duringCancel = () -> {};
        }

        public PgApprovalResult approve(PgApproveCommand c) {
            txSeen |= TransactionSynchronizationManager.isActualTransactionActive();
            approvals.incrementAndGet();
            lastApprove = c;
            var r = fake.approve(c);
            return wrongAmount
                    ? new PgApprovalResult.Approved(c.paymentKey(), c.pgOrderId(), 1, NOW)
                    : approvedAt != null
                            ? new PgApprovalResult.Approved(
                                    c.paymentKey(), c.pgOrderId(), c.amount(), approvedAt)
                            : r;
        }

        public PgQueryResult query(String id) {
            return fake.query(id);
        }

        public PgCancelResult cancel(PgCancelCommand c) {
            txSeen |= TransactionSynchronizationManager.isActualTransactionActive();
            cancels.incrementAndGet();
            duringCancel.run();
            return switch (cancelMode) {
                case "REJECTED" -> new PgCancelResult.Rejected("CANCEL_REJECTED", "rejected");
                case "UNKNOWN" -> new PgCancelResult.Unknown("timeout", null);
                default -> fake.cancel(c);
            };
        }
    }
}
