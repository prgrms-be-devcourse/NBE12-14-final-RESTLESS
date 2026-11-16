package com.restless.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/** 공통 주문. 금액은 주문 항목 스냅샷의 합으로 정하고 바꾸지 않는다 (INV-08, INV-13). */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private OrderStatus status;

    private long totalAmount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    public static Order pendingPayment(Long userId, OrderType orderType, long totalAmount, Instant now) {
        Order order = new Order();
        order.userId = Objects.requireNonNull(userId, "userId");
        order.orderType = Objects.requireNonNull(orderType, "orderType");
        if (totalAmount < 0) throw new IllegalArgumentException("totalAmount must be nonnegative");
        order.totalAmount = totalAmount;
        order.status = OrderStatus.PENDING_PAYMENT;
        order.createdAt = Objects.requireNonNull(now, "now");
        order.updatedAt = now;
        return order;
    }

    /**
     * @return 상태가 바뀌었으면 true. 이미 target이면 false (같은 결과의 중복 반영, INV-11)
     * @throws IllegalStateException 허용되지 않는 전이
     */
    public boolean transitionTo(OrderStatus target, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        if (status == target) return false;
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid order transition: " + status + " -> " + target);
        }
        status = target;
        updatedAt = now;
        return true;
    }
}
