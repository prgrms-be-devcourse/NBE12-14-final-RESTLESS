package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.drop.application.dto.DropReservationResponse.*;
import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import com.restless.order.application.CreateOrderCommand;
import com.restless.order.application.OrderService;
import com.restless.order.domain.OrderType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** TX1. No external I/O; inventory, order, reservation and slot commit or roll back together. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class DropReservationTransactions {
    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropPurchaseSlotRepository slots;
    private final DropInventoryRepository inventories;
    private final DropReservationRepository reservations;
    private final OrderService orders;
    private final Clock clock;
    private final DropReservationSettings settings;
    private final DropQueueEntryRepository queueEntries;

    public Result create(Long dropId, Long userId, String key, CreateDropReservationRequest request) {
        var previous = findExisting(dropId, userId, key);
        if (previous.isPresent()) return new Result(previous.get(), true);

        var drop = drops.findById(dropId).filter(d -> d.getStatus().isPublished())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requireOnSale(drop, clock.instant());
        var variant = variants.findById(request.variantId())
                .filter(v -> v.getDropId().equals(dropId) && v.isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        slots.ensureExists(dropId, userId, clock.instant());
        var slot = slots.lockById(new DropPurchaseSlot.Id(dropId, userId));
        // READ_COMMITTED sees the winner's committed reservation after waiting for its slot lock.
        previous = findExisting(dropId, userId, key);
        if (previous.isPresent()) return new Result(previous.get(), true);
        if (slot.getActiveReservationId() != null) {
            throw new BusinessException(ErrorCode.DROP_ACTIVE_PURCHASE_EXISTS);
        }
        var now = clock.instant();
        requireOnSale(drop, now);
        DropQueueEntry admission = null;
        if (settings.queueRequired()) {
            admission = queueEntries.lockLatest(dropId, userId)
                    .filter(entry -> entry.validAdmission(clock.instant()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED));
        }
        // Keep the most contended inventory lock at the end of validation (EXP-01).
        if (inventories.holdOne(variant.getId()) == 0) throw new BusinessException(ErrorCode.DROP_SOLD_OUT);
        // The UPDATE may have waited across closesAt; reject and roll back its decrement in that case.
        now = clock.instant();
        requireOnSale(drop, now);
        if (admission != null && !admission.validAdmission(now)) {
            throw new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED);
        }
        var orderId = orders.create(new CreateOrderCommand(userId, OrderType.DROP,
                List.of(new CreateOrderCommand.OrderLine("DROP_VARIANT", variant.getId(), drop.getTitle(),
                        variant.getOptionName(), variant.getPriceAmount(), 1)),
                new CreateOrderCommand.ShippingAddress(request.recipient(), request.phone(), request.address())));
        var reservation = reservations.saveAndFlush(DropReservation.reserved(dropId, variant.getId(), userId,
                orderId, key, now.plus(settings.ttl()), now));
        if (admission != null) {
            now = clock.instant();
            if (!admission.validAdmission(now)) throw new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED);
            admission.consume(now);
        }
        slot.occupy(reservation.getId(), now);
        return new Result(created(reservation), false);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<Created> findExisting(Long dropId, Long userId, String key) {
        return reservations.findByDropIdAndUserIdAndRequestKey(dropId, userId, key).map(this::created);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Detail get(Long dropId, Long userId, Long reservationId) {
        var reservation = reservations.findByIdAndDropIdAndUserId(reservationId, dropId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new Detail(reservation.getId(), reservation.getOrderId(), reservation.getVariantId(),
                reservation.getStatus(), amount(reservation), reservation.getExpiresAt());
    }

    private Created created(DropReservation reservation) {
        return new Created(reservation.getId(), reservation.getOrderId(), reservation.getStatus(),
                amount(reservation), reservation.getExpiresAt());
    }

    private long amount(DropReservation reservation) {
        return orders.find(reservation.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Drop order is missing")).totalAmount();
    }

    private void requireOnSale(Drop drop, Instant now) {
        if ((drop.getStatus() != DropStatus.SCHEDULED && drop.getStatus() != DropStatus.OPEN)
                || now.isBefore(drop.getOpensAt()) || !now.isBefore(drop.getClosesAt())) {
            throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        }
    }
}
