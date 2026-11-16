package com.restless.order.application;

import com.restless.order.domain.OrderType;

import java.util.List;
import java.util.Objects;

/**
 * 공통 주문 생성 요청. 금액·상품명은 호출 도메인이 서버에 저장된 값으로 채운다. 요청 본문 값을 그대로 넣지 않는다 (INV-08).
 *
 * @param shipping 배송지 스냅샷. 배송이 없는 주문(티켓)은 null
 */
public record CreateOrderCommand(Long userId, OrderType orderType, List<OrderLine> lines, ShippingAddress shipping) {
    public CreateOrderCommand {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(orderType, "orderType");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("order needs at least one line");
        lines = List.copyOf(lines);
    }

    /** @param optionName 옵션이 없으면 빈 문자열 */
    public record OrderLine(String itemType, Long itemRefId, String itemName, String optionName,
                            long unitPrice, int quantity) {
    }

    public record ShippingAddress(String recipient, String phone, String address) {
    }
}
