package com.restless.drop.application;

import com.restless.drop.domain.DropReservation;
import com.restless.drop.infrastructure.DropInventoryRepository;
import com.restless.drop.infrastructure.DropPurchaseSlotRepository;
import com.restless.order.application.OrderService;
import com.restless.order.domain.OrderStatus;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared atomic stock/order/slot settlement for payment callbacks, cancellation and expiry. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DropReservationSettlement {
    private final DropInventoryRepository inventories;
    private final OrderService orders;
    private final DropPurchaseSlotRepository slots;
    private final Clock clock;

    public void confirm(DropReservation reservation) {
        requireOne(inventories.confirm(reservation.getVariantId()));
        orders.changeStatus(reservation.getOrderId(), OrderStatus.PAID);
        clearSlot(reservation);
    }

    public void release(DropReservation reservation) {
        requireOne(inventories.release(reservation.getVariantId()));
        orders.changeStatus(reservation.getOrderId(), OrderStatus.CANCELLED);
        clearSlot(reservation);
    }

    public void restore(DropReservation reservation) {
        requireOne(inventories.restore(reservation.getVariantId()));
        orders.changeStatus(reservation.getOrderId(), OrderStatus.REFUNDED);
        // A confirmed purchase already released its slot; a newer purchase may own it now.
    }

    private void clearSlot(DropReservation reservation) {
        slots.clearIfOwned(reservation.getDropId(), reservation.getUserId(), reservation.getId(), clock.instant());
    }

    private void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("Drop inventory settlement failed");
    }
}
