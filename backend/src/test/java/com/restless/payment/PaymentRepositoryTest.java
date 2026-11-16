package com.restless.payment;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.concurrent.*;
import static com.restless.payment.PaymentTest.NOW;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
class PaymentRepositoryTest {
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    Long userId;

    @BeforeEach void setUp() {
        refunds.deleteAllInBatch();
        payments.deleteAllInBatch();
        jdbc.update("DELETE FROM users WHERE email = 'payment-test@example.com'");
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES ('payment-test@example.com', 'test-hash', 'payment-test', 'USER', 'ACTIVE', ?, ?)
            """, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'payment-test@example.com'", Long.class);
    }
    Payment attempt(String key) {
        return Payment.ready(PayableType.DROP, 1L, userId, 1000L, key, NOW.plusSeconds(60), NOW);
    }
    @Test void migrationsValidateAndFindersRoundTripInstants() {
        Payment p = payments.saveAndFlush(attempt("one"));
        Payment loaded = payments.findByPgOrderId(p.getPgOrderId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM payments WHERE id = ?",
            String.class, p.getId())).isEqualTo("2026-11-12 01:00:00");
        assertThat(loaded.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(payments.findByPayableTypeAndPayableIdAndIdempotencyKey(PayableType.DROP, 1L, "one"))
            .get().extracting(Payment::getId).isEqualTo(p.getId());
    }
    @Test void activeAttemptBlocksDuplicateButFailedAllowsRetry() {
        Payment first = payments.saveAndFlush(attempt("one"));
        assertThatThrownBy(() -> payments.saveAndFlush(attempt("two")))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payments_active_payable_key");
        first.fail("DECLINED", "confirmed", NOW);
        payments.saveAndFlush(first);
        assertThat(payments.saveAndFlush(attempt("two")).getId()).isNotNull();
    }
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED"}, mode = EnumSource.Mode.EXCLUDE)
    void everyLiveStatusBlocksNewAttempt(PaymentStatus status) {
        Payment first = attempt("one");
        switch (status) {
            case READY -> { }
            case PROCESSING -> first.startProcessing("pg-key", NOW);
            case UNKNOWN -> { first.startProcessing("pg-key", NOW); first.markUnknown(NOW); }
            default -> {
                first.startProcessing("pg-key", NOW);
                first.approve(NOW, NOW);
                if (status != PaymentStatus.APPROVED) first.requestCancel(NOW);
                if (status == PaymentStatus.CANCEL_UNKNOWN) first.markCancelUnknown(NOW);
            }
        }
        payments.saveAndFlush(first);
        assertThatThrownBy(() -> payments.saveAndFlush(attempt("two")))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payments_active_payable_key");
    }
    @Test void cancelledPaymentAllowsNewAttempt() {
        Payment first = attempt("one");
        first.startProcessing("pg-key", NOW);
        first.approve(NOW, NOW);
        first.requestCancel(NOW);
        first.completeCancel(NOW);
        payments.saveAndFlush(first);
        assertThat(payments.saveAndFlush(attempt("two")).getId()).isNotNull();
    }
    @Test void idempotencyKeyCannotBeReusedEvenAfterFailure() {
        Payment first = attempt("one");
        first.fail("DECLINED", "confirmed", NOW);
        payments.saveAndFlush(first);
        assertThatThrownBy(() -> payments.saveAndFlush(attempt("one")))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payments_payable_idempotency");
    }
    @Test void inv11_duplicateLockedApprovalIsAppliedOnce() {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW);
        payments.saveAndFlush(p);
        assertThat(approveUnderLock(p.getId())).isTrue();
        assertThat(approveUnderLock(p.getId())).isFalse();
        Payment loaded = payments.findById(p.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }
    @Test void oneActiveFullRefundPerPaymentAndFailedRefundAllowsRetry() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        PaymentRefund first = refunds.saveAndFlush(PaymentRefund.request(id, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-one", "reason", NOW));
        assertThatThrownBy(() -> refunds.saveAndFlush(PaymentRefund.request(id, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-two", "reason", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_refunds_active_payment_id");
        first.fail("REJECTED", "confirmed", NOW);
        refunds.saveAndFlush(first);
        refunds.saveAndFlush(PaymentRefund.request(id, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-two", "reason", NOW));
        assertThat(refunds.count()).isEqualTo(2);
    }
    @Test void refundIdempotencyKeyIsGloballyUnique() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        PaymentRefund first = PaymentRefund.request(id, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-one", "reason", NOW);
        first.fail("REJECTED", "confirmed", NOW);
        refunds.saveAndFlush(first);
        assertThatThrownBy(() -> refunds.saveAndFlush(PaymentRefund.request(id, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-one", "reason", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_refunds_idempotency_key");
    }

    @Test void paymentKeysDifferingOnlyInCaseAreDifferentAttempts() {
        Payment lower = payments.saveAndFlush(attempt("key-a"));
        lower.fail("SUPERSEDED", "replaced", NOW);
        payments.saveAndFlush(lower);
        Payment upper = payments.saveAndFlush(attempt("KEY-A"));
        assertThat(upper.getId()).isNotEqualTo(lower.getId());
        assertThat(payments.findByPayableTypeAndPayableIdAndIdempotencyKey(PayableType.DROP, 1L, "key-a"))
            .get().extracting(Payment::getId).isEqualTo(lower.getId());
        assertThat(payments.findByPayableTypeAndPayableIdAndIdempotencyKey(PayableType.DROP, 1L, "KEY-A"))
            .get().extracting(Payment::getId).isEqualTo(upper.getId());
    }
    @Test void refundKeysDifferingOnlyInCaseAreDifferentRequests() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        PaymentRefund lower = PaymentRefund.requestCustomer(id, 1000L, "refund-key", "reason", NOW);
        lower.fail("REJECTED", "confirmed", NOW);
        refunds.saveAndFlush(lower);
        PaymentRefund upper = refunds.saveAndFlush(PaymentRefund.requestCustomer(id, 1000L, "REFUND-KEY", "reason", NOW));
        assertThat(refunds.findByPaymentIdAndKindAndClientKey(id, PaymentRefundKind.CUSTOMER_REFUND, "refund-key"))
            .get().extracting(PaymentRefund::getId).isEqualTo(lower.getId());
        assertThat(refunds.findByPaymentIdAndKindAndClientKey(id, PaymentRefundKind.CUSTOMER_REFUND, "REFUND-KEY"))
            .get().extracting(PaymentRefund::getId).isEqualTo(upper.getId());
    }

    /** Locked load + entity method: the second transaction waits, then sees APPROVED and does nothing. */
    boolean approveUnderLock(Long id) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Payment locked = payments.findByIdForUpdate(id).orElseThrow();
            if (locked.getStatus() != PaymentStatus.PROCESSING) return false;
            locked.approve(NOW.plusSeconds(1), NOW.plusSeconds(1));
            return true;
        });
    }
    @Test void inv11_concurrentLockedApprovalHasExactlyOneWinner() throws Exception {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW);
        payments.saveAndFlush(p);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> { start.await(); return approveUnderLock(p.getId()); };
            Future<Boolean> first = pool.submit(task);
            Future<Boolean> second = pool.submit(task);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        Payment loaded = payments.findById(p.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(loaded.getApprovedAt()).isEqualTo(NOW.plusSeconds(1));
    }
    @Test void dbRejectsProcessingWithoutPaymentKey() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'PROCESSING' WHERE id = ?", id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payments_payment_key_after_ready");
    }
    @Test void dbRejectsApprovedWithoutApprovedAt() {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW);
        Long id = payments.saveAndFlush(p).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'APPROVED' WHERE id = ?", id))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payments_approved_at");
    }
    @Test void dbRejectsCompletedRefundWithoutTransactionKey() {
        Long paymentId = payments.saveAndFlush(attempt("one")).getId();
        Long refundId = refunds.saveAndFlush(PaymentRefund.request(paymentId, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-one", "reason", NOW)).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_refunds SET status = 'COMPLETED' WHERE id = ?", refundId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payment_refunds_completed");
    }
    @Test void approvalStartTimeAndRefundKindRoundTrip() {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW.plusSeconds(5));
        Long paymentId = payments.saveAndFlush(p).getId();
        assertThat(payments.findById(paymentId).orElseThrow().getApprovalStartedAt()).isEqualTo(NOW.plusSeconds(5));
        Long refundId = refunds.saveAndFlush(
            PaymentRefund.request(paymentId, PaymentRefundKind.COMPENSATION, 1000L, "refund-one", "reason", NOW)).getId();
        assertThat(refunds.findById(refundId).orElseThrow().getKind()).isEqualTo(PaymentRefundKind.COMPENSATION);
    }
    @Test void dbRejectsPaymentKeyWithoutApprovalStartTimeAndViceVersa() {
        Long readyId = payments.saveAndFlush(attempt("one")).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET payment_key = 'pg-key' WHERE id = ?", readyId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payments_approval_started_with_key");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET approval_started_at = NOW(6) WHERE id = ?", readyId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payments_approval_started_with_key");
    }
    @Test void dbRejectsUnknownRefundKind() {
        Long paymentId = payments.saveAndFlush(attempt("one")).getId();
        Long refundId = refunds.saveAndFlush(
            PaymentRefund.request(paymentId, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "refund-one", "reason", NOW)).getId();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_refunds SET kind = 'PARTIAL' WHERE id = ?", refundId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_payment_refunds_kind");
    }
}
