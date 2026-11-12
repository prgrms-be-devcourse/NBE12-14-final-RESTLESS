package com.restless.payment;

import com.restless.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {
    @Test
    void allowsOnlyDocumentedTransitions() {
        Map<PaymentStatus, Set<PaymentStatus>> allowed = Map.of(
            PaymentStatus.READY, Set.of(PaymentStatus.PROCESSING, PaymentStatus.FAILED),
            PaymentStatus.PROCESSING, Set.of(PaymentStatus.APPROVED, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
            PaymentStatus.UNKNOWN, Set.of(PaymentStatus.APPROVED, PaymentStatus.FAILED),
            PaymentStatus.APPROVED, Set.of(PaymentStatus.CANCEL_PENDING),
            PaymentStatus.CANCEL_PENDING, Set.of(PaymentStatus.CANCELLED, PaymentStatus.CANCEL_UNKNOWN, PaymentStatus.APPROVED),
            PaymentStatus.CANCEL_UNKNOWN, Set.of(PaymentStatus.CANCELLED, PaymentStatus.APPROVED));
        for (var from : PaymentStatus.values()) {
            for (var to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                    .isEqualTo(allowed.getOrDefault(from, Set.of()).contains(to));
            }
        }
    }
}
