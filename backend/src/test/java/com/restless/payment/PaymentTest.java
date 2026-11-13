package com.restless.payment;

import com.restless.payment.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class PaymentTest {
    static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");
    static Payment ready() {
        return Payment.ready(PayableType.DROP, 1L, 1L, 1000L, "attempt", NOW.plusSeconds(60), NOW);
    }
    static Payment at(PaymentStatus status) {
        Payment p = ready();
        switch (status) {
            case READY -> { return p; }
            case FAILED -> { p.fail("EXPIRED", "expired", NOW); return p; }
            default -> p.startProcessing("key", NOW);
        }
        if (status == PaymentStatus.PROCESSING) return p;
        if (status == PaymentStatus.UNKNOWN) { p.markUnknown(NOW); return p; }
        p.approve(NOW, NOW);
        if (status == PaymentStatus.APPROVED) return p;
        p.requestCancel(NOW);
        if (status == PaymentStatus.CANCELLED) p.completeCancel(NOW);
        if (status == PaymentStatus.CANCEL_UNKNOWN) p.markCancelUnknown(NOW);
        return p;
    }
    static void transition(Payment p, PaymentStatus target) {
        switch (target) {
            case READY -> throw new IllegalArgumentException();
            case PROCESSING -> p.startProcessing("key", NOW);
            case APPROVED -> {
                if (p.getStatus() == PaymentStatus.CANCEL_PENDING || p.getStatus() == PaymentStatus.CANCEL_UNKNOWN)
                    p.revertCancel(NOW);
                else p.approve(NOW, NOW);
            }
            case FAILED -> p.fail("DECLINED", "confirmed failure", NOW);
            case UNKNOWN -> p.markUnknown(NOW);
            case CANCEL_PENDING -> p.requestCancel(NOW);
            case CANCELLED -> p.completeCancel(NOW);
            case CANCEL_UNKNOWN -> p.markCancelUnknown(NOW);
        }
    }
    @Test void enforcesEveryAllowedAndForbiddenTransition() {
        for (var from : PaymentStatus.values()) {
            for (var to : PaymentStatus.values()) {
                if (to == PaymentStatus.READY) continue;
                Payment p = at(from);
                if (from.canTransitionTo(to)) {
                    transition(p, to);
                    assertThat(p.getStatus()).isEqualTo(to);
                    assertThat(p.getUpdatedAt()).isEqualTo(NOW);
                } else {
                    assertThatIllegalStateException().as("%s -> %s", from, to)
                        .isThrownBy(() -> transition(p, to));
                    assertThat(p.getStatus()).isEqualTo(from);
                }
            }
        }
    }
    @Test void inv10_unknownDoesNotAutomaticallyBecomeFailed_withoutExplicitConfirmedResult() {
        Payment p = at(PaymentStatus.PROCESSING);
        p.markUnknown(NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(p.getFailureCode()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> p.startProcessing("retry", NOW));
        p.approve(NOW, NOW);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }
    @Test void rejectsProcessingAfterExpiryWithoutMutatingPaymentKey() {
        Payment p = ready();
        assertThatIllegalStateException().isThrownBy(() -> p.startProcessing("key", NOW.plusSeconds(61)));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(p.getPaymentKey()).isNull();
    }
    @Test void allowsProcessingAtExpiryAndWithoutExpiry() {
        ready().startProcessing("key", NOW.plusSeconds(60));
        Payment.ready(PayableType.TICKET, 2L, 1L, 100L, "key", null, NOW).startProcessing("key", NOW);
    }
    @Test void createsUniquePgOrderIdsAndStoresSnapshot() {
        Payment p = ready();
        assertThat(p.getPgOrderId()).matches("^[A-Za-z0-9_=-]{6,64}$").isNotEqualTo(ready().getPgOrderId());
        assertThat(p.getCreatedAt()).isEqualTo(NOW);
        assertThat(p.getPaymentKey()).isNull();
        assertThat(p.matchesAmount(1000)).isTrue();
        assertThat(p.matchesAmount(999)).isFalse();
        p.startProcessing("key", NOW);
        p.approve(NOW.plusSeconds(1), NOW.plusSeconds(2));
        assertThat(p.getApprovedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(p.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }
    @Test void rejectsNegativeAmountAndRecordsConfirmedFailure() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            Payment.ready(PayableType.DROP, 1L, 1L, -1L, "key", null, NOW));
        Payment p = at(PaymentStatus.UNKNOWN);
        p.fail("DECLINED", "confirmed by PG lookup", NOW.plusSeconds(1));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureCode()).isEqualTo("DECLINED");
        assertThat(p.getFailureMessage()).isEqualTo("confirmed by PG lookup");
    }
    @Test void cancellationRejectionPreservesOriginalApproval() {
        Payment p = at(PaymentStatus.APPROVED);
        p.requestCancel(NOW);
        p.revertCancel(NOW.plusSeconds(2));
        assertThat(p.getApprovedAt()).isEqualTo(NOW);
        assertThatIllegalStateException().isThrownBy(() -> p.revertCancel(NOW));
    }
    @Test void startProcessingRecordsApprovalStartTimeAndReadyClosureDoesNot() {
        Payment started = ready();
        assertThat(started.getApprovalStartedAt()).isNull();
        started.startProcessing("key", NOW.plusSeconds(10));
        assertThat(started.getApprovalStartedAt()).isEqualTo(NOW.plusSeconds(10));
        started.markUnknown(NOW.plusSeconds(20));
        started.fail("DECLINED", "confirmed by PG lookup", NOW.plusSeconds(30));
        assertThat(started.getApprovalStartedAt()).isEqualTo(NOW.plusSeconds(10));

        Payment closed = ready();
        closed.fail("EXPIRED", "expired before approval", NOW.plusSeconds(61));
        assertThat(closed.getApprovalStartedAt()).isNull();
    }
    @Test void rejectedStartAfterExpiryLeavesApprovalStartTimeEmpty() {
        Payment p = ready();
        assertThatIllegalStateException().isThrownBy(() -> p.startProcessing("key", NOW.plusSeconds(61)));
        assertThat(p.getApprovalStartedAt()).isNull();
    }
}
