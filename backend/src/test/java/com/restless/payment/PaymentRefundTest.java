package com.restless.payment;

import com.restless.payment.domain.*;
import org.junit.jupiter.api.Test;
import static com.restless.payment.PaymentTest.NOW;
import static org.assertj.core.api.Assertions.*;

class PaymentRefundTest {
    PaymentRefund at(PaymentRefundStatus status) {
        PaymentRefund r = PaymentRefund.request(1L, PaymentRefundKind.CUSTOMER_REFUND, 1000L, "key", "reason", NOW);
        switch (status) {
            case REQUESTED -> { }
            case UNKNOWN -> r.markUnknown(NOW);
            case COMPLETED -> r.complete("transaction", NOW);
            case FAILED -> r.fail("REJECTED", "confirmed", NOW);
        }
        return r;
    }
    @Test void enforcesAllRefundTransitions() {
        for (var from : PaymentRefundStatus.values()) {
            for (var to : PaymentRefundStatus.values()) {
                if (to == PaymentRefundStatus.REQUESTED) continue;
                PaymentRefund r = at(from);
                Runnable action = switch (to) {
                    case COMPLETED -> () -> r.complete("transaction", NOW.plusSeconds(1));
                    case UNKNOWN -> () -> r.markUnknown(NOW.plusSeconds(1));
                    case FAILED -> () -> r.fail("REJECTED", "confirmed", NOW.plusSeconds(1));
                    default -> throw new IllegalArgumentException();
                };
                boolean allowed = (from == PaymentRefundStatus.REQUESTED && to != PaymentRefundStatus.REQUESTED)
                    || (from == PaymentRefundStatus.UNKNOWN && (to == PaymentRefundStatus.COMPLETED || to == PaymentRefundStatus.FAILED));
                assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
                if (allowed) {
                    action.run();
                    assertThat(r.getStatus()).isEqualTo(to);
                    assertThat(r.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
                } else assertThatIllegalStateException().isThrownBy(action::run);
            }
        }
    }
    @Test void recordsCompletionAndFailureDetails() {
        PaymentRefund r = at(PaymentRefundStatus.UNKNOWN);
        assertThat(r.getFailureCode()).isNull();
        r.complete("transaction", NOW.plusSeconds(2));
        assertThat(r.getCancelTransactionKey()).isEqualTo("transaction");
        assertThat(r.getCompletedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(r.getRequestedAt()).isEqualTo(NOW);
        PaymentRefund failed = at(PaymentRefundStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("REJECTED");
        assertThat(failed.getFailureMessage()).isEqualTo("confirmed");
    }
    @Test void rejectsNonpositiveRefundAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentRefund.request(1L, PaymentRefundKind.CUSTOMER_REFUND, 0L, "key", "reason", NOW));
    }
    @Test void requiresRefundKind() {
        assertThat(PaymentRefund.request(1L, PaymentRefundKind.COMPENSATION, 1000L, "key", "reason", NOW).getKind())
            .isEqualTo(PaymentRefundKind.COMPENSATION);
        assertThatNullPointerException().isThrownBy(() -> PaymentRefund.request(1L, null, 1000L, "key", "reason", NOW));
    }
}
