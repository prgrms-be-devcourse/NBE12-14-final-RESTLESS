package com.restless.drop.application;

import com.restless.drop.domain.DropReservationStatus;
import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.member.application.WithdrawalGuard;
import com.restless.order.application.OrderService;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 재고 점유 또는 미확정 금전 처리가 남은 회원의 탈퇴를 막는다 (policy.md 11절). */
@Component
@RequiredArgsConstructor
public class DropWithdrawalGuard implements WithdrawalGuard {
    private static final Set<DropReservationStatus> ACTIVE_HOLDS = Set.of(
            DropReservationStatus.RESERVED, DropReservationStatus.CONFIRMING,
            DropReservationStatus.RECONCILING, DropReservationStatus.COMPENSATING);
    private static final Set<OrderStatus> UNSETTLED_ORDERS = Set.of(
            OrderStatus.PAYMENT_REVIEW, OrderStatus.REFUND_PENDING, OrderStatus.REFUND_REVIEW);

    private final DropReservationRepository reservations;
    private final OrderService orders;

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveParticipation(Long userId) {
        return reservations.existsByUserIdAndStatusIn(userId, ACTIVE_HOLDS)
                || orders.existsByUserAndTypeAndStatuses(userId, OrderType.DROP, UNSETTLED_ORDERS);
    }
}
