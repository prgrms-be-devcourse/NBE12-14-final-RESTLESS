package com.restless.drop.application;

import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.order.application.OrderPayable;
import com.restless.order.application.OrderPayableResolver;
import com.restless.order.domain.OrderType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 드롭 주문의 결제 대상은 그 주문을 만든 재고 점유다. 결제 기한은 점유 만료 시각이다 (common.md 주문 상세). */
@Component
@RequiredArgsConstructor
public class DropOrderPayableResolver implements OrderPayableResolver {
    static final String PAYABLE_TYPE = "DROP";

    private final DropReservationRepository reservations;

    @Override
    public OrderType orderType() {
        return OrderType.DROP;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderPayable> resolve(Long orderId) {
        return reservations.findByOrderId(orderId)
                .map(r -> new OrderPayable(PAYABLE_TYPE, r.getId(), r.getExpiresAt()));
    }
}
