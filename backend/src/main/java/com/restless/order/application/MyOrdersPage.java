package com.restless.order.application;

import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.time.Instant;
import java.util.List;

/** 내 주문 목록 전용 응답. 배송지와 결제 정보는 포함하지 않는다. */
public record MyOrdersPage(List<Summary> items, int page, int size, long totalElements, int totalPages) {
    public MyOrdersPage {
        items = List.copyOf(items);
    }

    public record Summary(Long orderId, OrderType orderType, OrderStatus status, long totalAmount,
                          Instant createdAt, List<Line> lines) {
        public Summary {
            lines = List.copyOf(lines);
        }
    }

    public record Line(String itemType, String itemName, String optionName, long unitPrice, int quantity) {}
}
