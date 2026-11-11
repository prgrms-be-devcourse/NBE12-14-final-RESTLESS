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
    int approve(Long id) {
        return new TransactionTemplate(transactionManager).execute(status -> payments.transitionStatus(
            id, List.of(PaymentStatus.PROCESSING), PaymentStatus.APPROVED, NOW.plusSeconds(1)));
    }
    @Test void inv11_duplicateConditionalApprovalUpdatesZeroRows() {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW);
        payments.saveAndFlush(p);
        assertThat(approve(p.getId())).isEqualTo(1);
        assertThat(approve(p.getId())).isZero();
        Payment loaded = payments.findById(p.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }
    @Test void inv11_concurrentApprovalHasExactlyOneWinner() throws Exception {
        Payment p = attempt("one");
        p.startProcessing("pg-key", NOW);
        payments.saveAndFlush(p);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> { start.await(); return approve(p.getId()); };
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(1, 0);
        }
    }
    @Test void oneActiveFullRefundPerPaymentAndFailedRefundAllowsRetry() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        PaymentRefund first = refunds.saveAndFlush(PaymentRefund.request(id, 1000L, "refund-one", "reason", NOW));
        assertThatThrownBy(() -> refunds.saveAndFlush(PaymentRefund.request(id, 1000L, "refund-two", "reason", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_refunds_active_payment_id");
        first.fail("REJECTED", "confirmed", NOW);
        refunds.saveAndFlush(first);
        refunds.saveAndFlush(PaymentRefund.request(id, 1000L, "refund-two", "reason", NOW));
        assertThat(refunds.count()).isEqualTo(2);
    }
    @Test void refundIdempotencyKeyIsGloballyUnique() {
        Long id = payments.saveAndFlush(attempt("one")).getId();
        PaymentRefund first = PaymentRefund.request(id, 1000L, "refund-one", "reason", NOW);
        first.fail("REJECTED", "confirmed", NOW);
        refunds.saveAndFlush(first);
        assertThatThrownBy(() -> refunds.saveAndFlush(PaymentRefund.request(id, 1000L, "refund-one", "reason", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_payment_refunds_idempotency_key");
    }
}
