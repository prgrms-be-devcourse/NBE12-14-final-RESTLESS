package com.restless.order.application;

import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.time.Instant;
import java.util.List;

/**
 * 주문 상세 응답 (common.md 주문 상세). 목록 한 줄에 배송지, 결제 대상, 최근 결제를 더한다.
 * 결제 가능 여부 같은 판단 값은 넣지 않는다. 최종 판단은 결제와 환불 API다.
 *
 * @param shipping 배송이 없는 주문은 null
 * @param payable 결제 대상을 알려 주는 도메인이 없으면 null
 * @param payableExpiresAt 결제 기한. 모르면 null
 * @param payment 결제를 시작하지 않았으면 null
 */
public record OrderDetail(Long orderId, OrderType orderType, OrderStatus status, long totalAmount, Instant createdAt,
                          List<MyOrdersPage.Line> lines, Shipping shipping, Payable payable, Instant payableExpiresAt,
                          Payment payment) {
    public OrderDetail {
        lines = List.copyOf(lines);
    }

    public record Shipping(String recipient, String phone, String address) {}

    public record Payable(String payableType, Long payableId) {}

    public record Payment(Long paymentId, String status) {}
}
