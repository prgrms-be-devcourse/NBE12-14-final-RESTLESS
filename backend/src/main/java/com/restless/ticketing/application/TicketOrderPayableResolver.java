package com.restless.ticketing.application;

import com.restless.order.application.OrderPayable;
import com.restless.order.application.OrderPayableResolver;
import com.restless.order.domain.OrderType;
import com.restless.payment.domain.PayableType;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 주문 상세의 결제 대상 식별. 결제 자격 판정과 구분해 만료·확정 후에도 연결 정보를 제공한다. */
@Component
@RequiredArgsConstructor
public class TicketOrderPayableResolver implements OrderPayableResolver {
    private final TicketReservationRepository reservations;

    @Override
    public OrderType orderType() {
        return OrderType.TICKET;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderPayable> resolve(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Optional.empty();
        }
        return reservations.findByOrderId(orderId)
                .map(reservation -> new OrderPayable(PayableType.TICKET.name(), reservation.getId(),
                        reservation.getPaymentExpiresAt()));
    }
}
