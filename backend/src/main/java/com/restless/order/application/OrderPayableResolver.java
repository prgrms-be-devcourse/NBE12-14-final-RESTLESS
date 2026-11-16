package com.restless.order.application;

import com.restless.order.domain.OrderType;
import java.util.Optional;

/**
 * 주문의 결제 대상을 아는 도메인이 구현한다 (common.md 주문 상세, #109).
 * 공통 주문은 도메인을 모르므로, 도메인이 이 인터페이스를 {@code @Component}로 구현해 알려 준다.
 * 구현이 없는 주문 종류는 주문 상세의 {@code payable}이 null이다.
 */
public interface OrderPayableResolver {

    /** 이 구현이 맡는 주문 종류. 종류마다 구현은 하나다. */
    OrderType orderType();

    /** 주문 ID로 결제 대상을 찾는다. 읽기만 한다. 없으면 비어 있다. */
    Optional<OrderPayable> resolve(Long orderId);
}
