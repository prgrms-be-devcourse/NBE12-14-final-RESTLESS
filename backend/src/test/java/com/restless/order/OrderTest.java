package com.restless.order;

import com.restless.order.domain.Order;
import com.restless.order.domain.OrderItem;
import com.restless.order.domain.OrderShippingSnapshot;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.restless.order.domain.OrderStatus.*;
import static org.assertj.core.api.Assertions.*;

class OrderTest {
    static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");

    static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PENDING_PAYMENT, EnumSet.of(PAYMENT_CONFIRMING, CANCELLED),
        PAYMENT_CONFIRMING, EnumSet.of(PAID, PAYMENT_REVIEW, CANCELLED, PENDING_PAYMENT),
        PAYMENT_REVIEW, EnumSet.of(PAID, CANCELLED, PENDING_PAYMENT),
        PAID, EnumSet.of(REFUND_PENDING),
        REFUND_PENDING, EnumSet.of(REFUNDED, REFUND_REVIEW),
        REFUND_REVIEW, EnumSet.of(REFUNDED, PAID),
        CANCELLED, EnumSet.noneOf(OrderStatus.class),
        REFUNDED, EnumSet.noneOf(OrderStatus.class));

    @Test void statusTransitionsMatchStateMachineDocument() {
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
        for (var from : OrderStatus.values()) {
            for (var to : OrderStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(ALLOWED.get(from).contains(to));
            }
            assertThat(from.canTransitionTo(null)).isFalse();
        }
    }

    @Test void newOrderWaitsForPayment() {
        Order order = Order.pendingPayment(1L, OrderType.DROP, 39000, NOW);
        assertThat(order.getStatus()).isEqualTo(PENDING_PAYMENT);
        assertThat(order.getTotalAmount()).isEqualTo(39000);
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
        assertThat(order.getUpdatedAt()).isEqualTo(NOW);
        assertThatIllegalArgumentException().isThrownBy(() -> Order.pendingPayment(1L, OrderType.DROP, -1, NOW));
        assertThatNullPointerException().isThrownBy(() -> Order.pendingPayment(null, OrderType.DROP, 0, NOW));
    }

    @Test void transitionUpdatesTimeAndRepeatedTargetIsNoop() {
        Order order = Order.pendingPayment(1L, OrderType.DROP, 1000, NOW);
        assertThat(order.transitionTo(PAYMENT_CONFIRMING, NOW.plusSeconds(1))).isTrue();
        assertThat(order.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(order.transitionTo(PAYMENT_CONFIRMING, NOW.plusSeconds(2))).isFalse();
        assertThat(order.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test void forbiddenTransitionKeepsState() {
        Order order = Order.pendingPayment(1L, OrderType.TICKET, 1000, NOW);
        assertThatIllegalStateException().isThrownBy(() -> order.transitionTo(PAID, NOW.plusSeconds(1)))
            .withMessageContaining("PENDING_PAYMENT -> PAID");
        assertThat(order.getStatus()).isEqualTo(PENDING_PAYMENT);
        assertThat(order.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test void retryAfterFailedAttemptReturnsToPendingPayment() {
        Order order = Order.pendingPayment(1L, OrderType.DROP, 1000, NOW);
        order.transitionTo(PAYMENT_CONFIRMING, NOW);
        order.transitionTo(PENDING_PAYMENT, NOW);
        order.transitionTo(PAYMENT_CONFIRMING, NOW);
        order.transitionTo(PAID, NOW);
        assertThat(order.getStatus()).isEqualTo(PAID);
    }

    @Test void itemSnapshotValidatesValuesAndAmount() {
        OrderItem item = OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "후드티", "M", 39000, 1);
        assertThat(item.lineAmount()).isEqualTo(39000);
        assertThat(OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "포토카드", "", 0, 1).getOptionName()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, " ", "M", 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "후드티", "M", -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "후드티", "M", 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "가".repeat(201), "M", 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "후드티", "가".repeat(101), 1, 1));
        assertThatNullPointerException().isThrownBy(() -> OrderItem.snapshot(1L, "DROP_VARIANT", 7L, "후드티", null, 1, 1));
        assertThatExceptionOfType(ArithmeticException.class)
            .isThrownBy(() -> OrderItem.snapshot(1L, "SEAT", 7L, "좌석", "", Long.MAX_VALUE, 2).lineAmount());
    }

    @Test void shippingSnapshotRequiresAllFieldsWithinLimits() {
        var snapshot = OrderShippingSnapshot.of(1L, "홍길동", "010-1234-5678", "서울시 강남구");
        assertThat(snapshot.getId()).isEqualTo(1L);
        assertThat(snapshot.isNew()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> OrderShippingSnapshot.of(1L, "", "010", "주소"));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderShippingSnapshot.of(1L, "홍길동", "0".repeat(21), "주소"));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderShippingSnapshot.of(1L, "홍길동", "010", "가".repeat(256)));
    }
}
