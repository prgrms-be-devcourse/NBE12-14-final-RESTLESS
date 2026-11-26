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
public class DropQueueService {
    private final DropRepository drops;
    private final DropVariantRepository variants;
    private final DropInventoryRepository inventories;
    private final DropSoldOutCache soldOut;
    private final DropQueueEntryRepository entries;
    private final DropPurchaseSlotRepository slots;
    private final DropQueueSettings settings;
    private final DropQueueMetrics metrics;
    private final DropQueuePositions positions;
    private final DropQueuePollPolicy pollPolicy;
    private final Clock clock;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(DropQueueStatus status, Long variantId, Long position, Instant admittedUntil, Long pollAfterMs) { }
    public record Entered(View body, boolean existing) { }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entered enter(Long dropId, Long userId, Long variantId) {
        var drop = drops.findById(dropId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!onSale(drop, clock.instant())) throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        variants.findById(variantId).filter(v -> v.getDropId().equals(dropId) && v.isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inventories.available(variantId) == 0) {
            // 드롭 전체가 품절이면 다음 진입부터 필터가 바로 409로 끝낸다 (#481).
            soldOut.observeVariantSoldOut(dropId);
            throw new BusinessException(ErrorCode.DROP_SOLD_OUT);
        }
        // Serialize concurrent first entries using the existing same-member purchase slot.
        slots.ensureExists(dropId, userId, clock.instant());
        slots.lockById(new DropPurchaseSlot.Id(dropId, userId));
        var previous = entries.lockLatest(dropId, userId);
        var now = clock.instant();
        if (!onSale(drop, now)) throw new BusinessException(ErrorCode.DROP_NOT_ON_SALE);
        if (previous.isPresent()) {
            var entry = previous.get();
            refresh(entry, now, true);
            if (entry.live() && entry.getVariantId().equals(variantId)) return new Entered(view(entry, now, entry.getStatus()), true);
            entry.close();
            entries.flush(); // Release the generated active key before replacement.
        }
        // Capture committed queue ids before this transaction inserts its own row.
        var snapshot = positions.snapshot(variantId);
        var entry = entries.saveAndFlush(DropQueueEntry.waiting(dropId, userId, variantId, now));
        metrics.entered();
        return new Entered(waitingView(entry, now, snapshot), false);
    }
    public Optional<View> me(Long dropId, Long userId) {
        var latest = entries.latest(dropId, userId);
        if (latest.isEmpty()) return Optional.empty();
        var row = latest.get();
        var now = clock.instant();
        var status = effectiveStatus(row, now);
        if (status == DropQueueStatus.WAITING && row.getEntry().heartbeatDue(now, settings.heartbeatInterval())) {
            int updated = entries.recordHeartbeat(row.getEntry().getId(), now, now.minus(settings.heartbeatTimeout()),
                    now.minus(settings.heartbeatInterval()));
            if (updated == 0) {
                latest = entries.latest(dropId, userId);
                if (latest.isEmpty()) return Optional.empty();
                row = latest.get();
                now = clock.instant();
                status = effectiveStatus(row, now);
            }
        }
        return status == DropQueueStatus.CLOSED ? Optional.empty() : Optional.of(view(row.getEntry(), now, status));
    }

    private DropQueueStatus effectiveStatus(DropQueueEntryRepository.Latest row, Instant now) {
        return row.getEntry().effectiveStatus(now, settings.heartbeatTimeout(), row.getSale().isOnSale(now));
    }

    private void refresh(DropQueueEntry entry, Instant now, boolean sale) {
        var before = entry.getStatus();
        entry.refresh(now, settings.heartbeatTimeout(), sale);
        if (before != entry.getStatus()) metrics.closed(entry.getStatus() == DropQueueStatus.EXPIRED
                ? "expired" : sale ? "heartbeat" : "sale_ended");
    }
    private View view(DropQueueEntry entry, Instant now, DropQueueStatus status) {
        if (status == DropQueueStatus.WAITING)
            return waitingView(entry, now, positions.snapshot(entry.getVariantId()));
        return new View(status, entry.getVariantId(), null,
                status == DropQueueStatus.ADMITTED ? entry.getAdmittedUntil() : null, null);
    }
    private View waitingView(DropQueueEntry entry, Instant now, DropQueuePositions.Snapshot snapshot) {
        long position = positions.position(entry.getVariantId(), entry.getId(), now, snapshot);
        long shown = Math.max(1, position);
        return new View(entry.getStatus(), entry.getVariantId(), shown, null, pollPolicy.afterMs(shown));
    }
    private boolean onSale(Drop drop, Instant now) {
        return drop.isOnSale(now);
    }
}
