package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import com.restless.order.application.OrderService;
import com.restless.order.application.OrderView;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DropReservationTransactionsTest {
    @Test
    void inv14InventoryLockWaitCrossingSaleCloseRejectsBeforeOrderCreation() {
        var drops = mock(DropRepository.class);
        var variants = mock(DropVariantRepository.class);
        var slots = mock(DropPurchaseSlotRepository.class);
        var inventories = mock(DropInventoryRepository.class);
        var reservations = mock(DropReservationRepository.class);
        var orders = mock(OrderService.class);
        var clock = mock(Clock.class);
        var opens = Instant.EPOCH;
        var closes = opens.plusSeconds(60);
        var drop = Drop.draft(1L, "상품", "설명", "url", opens, closes, opens);
        drop.publish();
        var variant = mock(DropVariant.class);
        when(variant.getId()).thenReturn(3L);
        when(variant.getDropId()).thenReturn(1L);
        when(variant.isActive()).thenReturn(true);
        when(drops.findById(1L)).thenReturn(Optional.of(drop));
        when(variants.findById(3L)).thenReturn(Optional.of(variant));
        var slot = mock(DropPurchaseSlot.class);
        when(slot.getActiveReservationId()).thenReturn(null);
        when(slots.lockById(new DropPurchaseSlot.Id(1L, 2L))).thenReturn(slot);
        when(clock.instant()).thenReturn(opens);
        when(inventories.holdOne(3L)).thenAnswer(call -> {
            // The inventory UPDATE returns only after the competing transaction releases its lock.
            when(clock.instant()).thenReturn(closes);
            return 1;
        });
        when(orders.create(any())).thenReturn(7L);
        when(orders.find(7L)).thenReturn(Optional.of(new OrderView(7L, 2L, OrderType.DROP, OrderStatus.PENDING_PAYMENT, 0)));
        when(reservations.saveAndFlush(any(DropReservation.class))).thenAnswer(call -> call.getArgument(0));
        var service = new DropReservationTransactions(drops, variants, slots, inventories, reservations,
                orders, clock, new DropReservationSettings(Duration.ofMinutes(5), false), mock(DropQueueEntryRepository.class));

        assertThatThrownBy(() -> service.create(1L, 2L, "key",
                new CreateDropReservationRequest(3L, "회원", "010", "서울")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DROP_NOT_ON_SALE));
        verifyNoInteractions(orders);
        verify(reservations, never()).saveAndFlush(any());
    }

    @Test
    void inv06InventoryLockWaitCrossingAdmissionExpiryRejectsBeforeOrderCreation() {
        var drops = mock(DropRepository.class);
        var variants = mock(DropVariantRepository.class);
        var slots = mock(DropPurchaseSlotRepository.class);
        var inventories = mock(DropInventoryRepository.class);
        var reservations = mock(DropReservationRepository.class);
        var orders = mock(OrderService.class);
        var clock = mock(Clock.class);
        var opens = Instant.EPOCH;
        var closes = opens.plusSeconds(600);
        var drop = Drop.draft(1L, "상품", "설명", "url", opens, closes, opens);
        drop.publish();
        var variant = mock(DropVariant.class);
        when(variant.getId()).thenReturn(3L);
        when(variant.getDropId()).thenReturn(1L);
        when(variant.isActive()).thenReturn(true);
        when(drops.findById(1L)).thenReturn(Optional.of(drop));
        when(variants.findById(3L)).thenReturn(Optional.of(variant));
        var slot = mock(DropPurchaseSlot.class);
        when(slot.getActiveReservationId()).thenReturn(null);
        when(slots.lockById(new DropPurchaseSlot.Id(1L, 2L))).thenReturn(slot);
        when(clock.instant()).thenReturn(opens);
        when(inventories.holdOne(3L)).thenAnswer(call -> {
            // The inventory UPDATE returns only after the competing transaction releases its lock.
            when(clock.instant()).thenReturn(opens.plusSeconds(300));
            return 1;
        });
        when(orders.create(any())).thenReturn(7L);
        when(orders.find(7L)).thenReturn(Optional.of(new OrderView(7L, 2L, OrderType.DROP, OrderStatus.PENDING_PAYMENT, 0)));
        when(reservations.saveAndFlush(any(DropReservation.class))).thenAnswer(call -> call.getArgument(0));
        var queue = mock(DropQueueEntryRepository.class);
        var admission = DropQueueEntry.waiting(1L, 2L, opens);
        admission.admit(opens, Duration.ofMinutes(5));
        when(queue.lockLatest(1L, 2L)).thenReturn(Optional.of(admission));
        var service = new DropReservationTransactions(drops, variants, slots, inventories, reservations,
                orders, clock, new DropReservationSettings(Duration.ofMinutes(5), true), queue);

        assertThatThrownBy(() -> service.create(1L, 2L, "key",
                new CreateDropReservationRequest(3L, "회원", "010", "서울")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DROP_ADMISSION_REQUIRED));
        assertThat(admission.getStatus()).isEqualTo(DropQueueStatus.ADMITTED);
        verifyNoInteractions(orders);
        verify(reservations, never()).saveAndFlush(any());
    }
}
