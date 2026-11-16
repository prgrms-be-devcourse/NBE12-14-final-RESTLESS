package com.restless.order.application;

import com.restless.order.domain.Order;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;

/** 다른 도메인에 넘기는 주문 요약. 엔티티를 밖으로 내보내지 않는다. */
public record OrderView(Long orderId, Long userId, OrderType orderType, OrderStatus status, long totalAmount) {
    static OrderView from(Order order) {
        return new OrderView(order.getId(), order.getUserId(), order.getOrderType(), order.getStatus(),
            order.getTotalAmount());
    }
}
