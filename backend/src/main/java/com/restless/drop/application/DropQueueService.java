package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DropQueueService {
    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropInventoryRepository inventories;
    private final DropQueueEntryRepository entries;
    private final DropPurchaseSlotRepository slots;
    private final DropQueueSettings settings;
    private final DropQueueMetrics metrics;
    private final DropQueuePositions positions;
    private final Clock clock;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(DropQueueStatus status, Long variantId, Long position, Instant admittedUntil) { }
    public record Entered(View body, boolean existing) { }
    public Entered enter(Long dropId, Long userId, Long variantId) {
        var drop = drops.findById(dropId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!onSale(drop, clock.instant())) throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        variants.findById(variantId).filter(v -> v.getDropId().equals(dropId) && v.isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inventories.available(variantId) == 0) throw new BusinessException(ErrorCode.DROP_SOLD_OUT);
        // Serialize concurrent first entries using the existing same-member purchase slot.
        slots.ensureExists(dropId, userId, clock.instant());
        slots.lockById(new DropPurchaseSlot.Id(dropId, userId));
        var previous = entries.lockLatest(dropId, userId);
        var now = clock.instant();
        if (!onSale(drop, now)) throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        if (previous.isPresent()) {
            var entry = previous.get();
            refresh(entry, now, true);
            if (entry.live() && entry.getVariantId().equals(variantId)) return new Entered(view(entry, now), true);
            entry.close();
            entries.flush(); // Release the generated active key before replacement.
        }
        // Capture committed queue ids before this transaction inserts its own row.
        var snapshot = positions.snapshot(variantId);
        var entry = entries.saveAndFlush(DropQueueEntry.waiting(dropId, userId, variantId, now));
        metrics.entered();
        return new Entered(waitingView(entry, now, snapshot), false);
    }
    // Commit CLOSED before the controller produces a 404.
    public Optional<View> me(Long dropId, Long userId) {
        var entry = entries.lockLatest(dropId, userId);
        if (entry.isEmpty()) return Optional.empty();
        var now = clock.instant();
        boolean sale = drops.findById(dropId).map(drop -> onSale(drop, now)).orElse(false);
        refresh(entry.get(), now, sale);
        if (entry.get().getStatus() == DropQueueStatus.CLOSED) return Optional.empty();
        var result = view(entry.get(), now);
        if (entry.get().live()) entry.get().heartbeat(now);
        return Optional.of(result);
    }
    private void refresh(DropQueueEntry entry, Instant now, boolean sale) {
        var before = entry.getStatus();
        entry.refresh(now, settings.heartbeatTimeout(), sale);
        if (before != entry.getStatus()) metrics.closed(entry.getStatus() == DropQueueStatus.EXPIRED
                ? "expired" : sale ? "heartbeat" : "sale_ended");
    }
    private View view(DropQueueEntry entry, Instant now) {
        if (entry.getStatus() == DropQueueStatus.WAITING)
            return waitingView(entry, now, positions.snapshot(entry.getVariantId()));
        return new View(entry.getStatus(), entry.getVariantId(), null,
                entry.getStatus() == DropQueueStatus.ADMITTED ? entry.getAdmittedUntil() : null);
    }
    private View waitingView(DropQueueEntry entry, Instant now, DropQueuePositions.Snapshot snapshot) {
        long position = positions.position(entry.getVariantId(), entry.getId(), now, snapshot);
        return new View(entry.getStatus(), entry.getVariantId(), position, null);
    }
    private boolean onSale(Drop drop, Instant now) {
        return drop.isOnSale(now);
    }
}
