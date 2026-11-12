package com.restless.payment.application.payable;

import com.restless.payment.domain.PaymentRefundKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PayableRecordsTest {
    static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");

    @Test void snapshotAcceptsMissingDeadlineAndRejectsInvalidValues() {
        assertThat(new PayableSnapshot(1L, 39000, "후드티 M", null).expiresAt()).isNull();
        assertThat(new PayableSnapshot(1L, 0, "무료 응모", NOW).amount()).isZero();
        assertThatNullPointerException().isThrownBy(() -> new PayableSnapshot(null, 1000, "상품", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new PayableSnapshot(1L, -1, "상품", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new PayableSnapshot(1L, 1000, " ", NOW));
    }

    @Test void snapshotOrderNameFollowsTossLimit() {
        assertThat(new PayableSnapshot(1L, 1000, "가".repeat(100), NOW).orderName()).hasSize(100);
        assertThatIllegalArgumentException().isThrownBy(() -> new PayableSnapshot(1L, 1000, "가".repeat(101), NOW));
    }

    @Test void rejectAlwaysCarriesReasonAndAcceptNever() {
        assertThat(ApprovalDecision.accept().accepted()).isTrue();
        assertThat(ApprovalDecision.accept().reason()).isNull();
        var rejected = ApprovalDecision.reject("RESERVATION_EXPIRED");
        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.reason()).isEqualTo("RESERVATION_EXPIRED");
        assertThatIllegalArgumentException().isThrownBy(() -> ApprovalDecision.reject(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> new ApprovalDecision(true, "reason"));
    }

    static final Instant STARTED = NOW.minusSeconds(30);

    @Test void approvedPaymentRequiresPgIdentityAndBothTimes() {
        var approved = new ApprovedPayment(1L, "PAY-1", "pk", 1000, STARTED, NOW);
        assertThat(approved.approvedAt()).isEqualTo(NOW);
        assertThat(approved.attempt()).isEqualTo(new PaymentAttemptContext(1L, STARTED));
        assertThatNullPointerException().isThrownBy(() -> new ApprovedPayment(1L, "PAY-1", null, 1000, STARTED, NOW));
        assertThatNullPointerException().isThrownBy(() -> new ApprovedPayment(1L, "PAY-1", "pk", 1000, null, NOW));
        assertThatNullPointerException().isThrownBy(() -> new ApprovedPayment(1L, "PAY-1", "pk", 1000, STARTED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ApprovedPayment(1L, "PAY-1", "pk", -1, STARTED, NOW));
    }

    @Test void attemptAlwaysCarriesPaymentIdButStartTimeOnlyAfterApprovalStarted() {
        assertThat(new PaymentAttemptContext(7L, STARTED).approvalStarted()).isTrue();
        var notStarted = PaymentAttemptContext.notStarted(7L);
        assertThat(notStarted.paymentId()).isEqualTo(7L);
        assertThat(notStarted.approvalStarted()).isFalse();
        assertThatNullPointerException().isThrownBy(() -> new PaymentAttemptContext(null, STARTED));
    }

    @Test void refundContextRequiresPaymentIdAndKind() {
        assertThat(new RefundContext(7L, PaymentRefundKind.COMPENSATION).kind()).isEqualTo(PaymentRefundKind.COMPENSATION);
        assertThatNullPointerException().isThrownBy(() -> new RefundContext(null, PaymentRefundKind.CUSTOMER_REFUND));
        assertThatNullPointerException().isThrownBy(() -> new RefundContext(7L, null));
    }
}
