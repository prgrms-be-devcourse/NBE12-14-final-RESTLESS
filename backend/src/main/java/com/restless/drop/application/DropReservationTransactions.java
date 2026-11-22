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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final MeterRegistry meters;

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
                    .filter(entry -> entry.getVariantId().equals(variant.getId()) && entry.validAdmission(clock.instant()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED));
        }
        if (admission != null && !admission.validAdmission(now)) {
            throw new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED);
        }
        var orderId = orders.create(new CreateOrderCommand(userId, OrderType.DROP,
                List.of(new CreateOrderCommand.OrderLine("DROP_VARIANT", variant.getId(), drop.getTitle(),
                        variant.getOptionName(), variant.getPriceAmount(), 1)),
                new CreateOrderCommand.ShippingAddress(request.recipient(), request.phone(), request.address())));
        var reservation = reservations.saveAndFlush(DropReservation.reserved(dropId, variant.getId(), userId,
                orderId, key, now.plus(settings.ttl()), now));
        if (admission != null) admission.consume(now);
        slot.occupy(reservation.getId(), now);
        // Built here because it reads the order amount, which would be one more round trip inside the lock.
        var response = created(reservation);
        // #322: every other write reaches the database before the most contended rows are locked, so a bucket
        // lock covers only the hold UPDATE and the commit. A rejection below rolls all of them back.
        slots.flush();
        long lockAcquired = holdOneUnit(variant.getId(), reservation.getId());
        recordInventoryLockHeld(lockAcquired);
        // The hold may have waited across closesAt or the admission deadline (INV-14, INV-06).
        // These checks read only the clock, so they add no database round trip inside the lock.
        var afterLock = clock.instant();
        requireOnSale(drop, afterLock);
        if (admission != null && !afterLock.isBefore(admission.getAdmittedUntil())) {
            throw new BusinessException(ErrorCode.DROP_ADMISSION_REQUIRED);
        }
        return new Result(response, false);
    }

    /**
     * TX1 steps 5 to 9-1 of ADR-0005 / design #327 r3. Returns the time the held bucket's lock was acquired.
     * <ul>
     * <li>6: pick an unlocked stock bucket with {@code FOR UPDATE SKIP LOCKED}, from a random start, never waiting.
     * <li>7: one UPDATE takes the unit from that bucket and records it on the reservation. Already locked: must hit.
     * <li>8: if every stock bucket is locked, read committed stock without locks. None visible: sold out.
     * <li>9: wait on the first bucket that showed stock. 9-1: if it emptied meanwhile, its lock stays until commit;
     *     pick again, skipping buckets already waited on. At most one wait per bucket, so at most K waits.
     * </ul>
     * No deadlock: the only wait is step 9, on a bucket whose committed stock this transaction saw, and the only
     * other bucket locks it holds are emptied buckets left by 9-1, which stay 0 while held.
     */
    private long holdOneUnit(Long variantId, Long reservationId) {
        int buckets = inventories.bucketCount(variantId);
        int from = ThreadLocalRandom.current().nextInt(buckets);
        Set<Integer> waited = new HashSet<>();
        long started = System.nanoTime();
        while (true) {
            var picked = inventories.pickFrom(variantId, from);
            if (picked.isEmpty() && from > 0) picked = inventories.pickBefore(variantId, from);
            if (picked.isPresent()) {
                if (inventories.holdInBucket(reservationId, variantId, picked.get().intValue()) < 1) {
                    throw new IllegalStateException("Locked drop inventory bucket had no stock");
                }
                return recordHold(started, waited.isEmpty() ? "picked" : "waited");
            }
            Integer target = inventories.stockedBuckets(variantId, from).stream().map(Number::intValue)
                    .filter(bucket -> !waited.contains(bucket)).findFirst().orElse(null);
            if (target == null) {
                meters.timer("drop.inventory.hold.update", "result", "sold_out")
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                meters.counter("drop.inventory.hold.path", "path", "sold_out").increment();
                throw new BusinessException(ErrorCode.DROP_SOLD_OUT);
            }
            waited.add(target);
            if (waited.size() > buckets) throw new IllegalStateException("Drop inventory hold waited more than K times");
            // Found-rows count is 2 here; 1 is possible with useAffectedRows. Either way at least 1 means held.
            if (inventories.holdInBucket(reservationId, variantId, target) >= 1) return recordHold(started, "waited");
        }
    }

    /** {@code hold.update} spans step 6 start to the successful hold, including any step 9 wait. */
    private long recordHold(long started, String path) {
        long lockAcquired = System.nanoTime();
        meters.timer("drop.inventory.hold.update", "result", "held")
                .record(lockAcquired - started, TimeUnit.NANOSECONDS);
        meters.counter("drop.inventory.hold.path", "path", path).increment();
        return lockAcquired;
    }

    /**
     * Times the held bucket's lock from its acquisition (the step 6 pick reply, or the step 9 UPDATE reply) to the
     * transaction ending (commit or rollback). It includes the step 7 UPDATE round trip. Measured in the
     * application, so it adds the replies and the commit round trip to the time the database holds the lock. Tags are bounded: committed, rolled_back, unknown.
     * #325: {@code drop.inventory.lock.completion} times only the last part, from the transaction manager
     * starting to complete (any flush, then the JDBC commit or rollback) to its end. The rest of
     * {@code lock.held} is the work between the lock and that point.
     */
    private void recordInventoryLockHeld(long lockAcquired) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private long completionStarted;

            @Override
            public void beforeCompletion() {
                completionStarted = System.nanoTime();
            }

            @Override
            public void afterCompletion(int status) {
                long ended = System.nanoTime();
                var outcome = status == STATUS_COMMITTED ? "committed"
                        : status == STATUS_ROLLED_BACK ? "rolled_back" : "unknown";
                meters.timer("drop.inventory.lock.held", "outcome", outcome)
                        .record(ended - lockAcquired, TimeUnit.NANOSECONDS);
                if (completionStarted != 0) {
                    meters.timer("drop.inventory.lock.completion", "outcome", outcome)
                            .record(ended - completionStarted, TimeUnit.NANOSECONDS);
                }
            }
        });
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
        if (!drop.isOnSale(now)) {
            throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        }
    }
}
