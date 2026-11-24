package com.restless.drop.application;

import com.restless.drop.domain.DropQueueStatus;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropQueueAdmissionTransactions {
    static final int CLOSE_BATCH_SIZE = 100;
    public enum Result { DONE, MORE, SKIPPED, MISSING }
    private final DropQueueGateRepository gates;
    private final DropQueueEntryRepository entries;
    private final DropVariantRepository variants;
    private final DropRepository drops;
    private final DropInventoryRepository inventories;
    private final DropQueueSettings settings;
    private final DropQueueMetrics metrics;
    private final Clock clock;

    /** One short transaction per SKU batch. TX1 never touches the gate. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result runBatch(Long variantId) {
        var gate = gates.tryLock(variantId);
        if (gate == DropQueueGateRepository.LockResult.MISSING) return Result.MISSING;
        if (gate == DropQueueGateRepository.LockResult.BUSY) {
            metrics.skipped();
            return Result.SKIPPED;
        }
        // INV-16: count first, then available. A concurrent TX1 can only cause under-issuance.
        long grants = entries.validGrantCount(variantId, clock.instant());
        int available = inventories.available(variantId);
        if (available == 0) {
            var batch = entries.lockLive(variantId, CLOSE_BATCH_SIZE);
            var closeNow = clock.instant();
            for (var entry : batch) {
                // An admission past its deadline is EXPIRED, as the user already saw, not UNAVAILABLE.
                if (entry.expire(closeNow)) {
                    metrics.closed("expired");
                } else {
                    entry.unavailable();
                    metrics.unavailable();
                }
            }
            entries.flush();
            return batch.size() == CLOSE_BATCH_SIZE ? Result.MORE : Result.DONE;
        }
        int allowance = (int) Math.min(Math.max(0L, available - grants), settings.admitPerRun());
        var variant = variants.findById(variantId).orElseThrow();
        var drop = drops.findById(variant.getDropId()).orElseThrow();
        // Lifecycle cleanup must run even if valid grants already cover all available stock.
        // Bound this batch too; MORE commits before processing any further stale rows.
        var cleanupNow = clock.instant();
        // Admissions past their deadline close as EXPIRED. They are not counted in c, so INV-16 is unaffected.
        // SKIP LOCKED is safe here: a row held by TX1 is CONSUMED or valid, and a skipped row waits for the next run.
        var lapsed = entries.lockExpiredAdmitted(variantId, cleanupNow, CLOSE_BATCH_SIZE);
        for (var entry : lapsed) {
            if (entry.expire(cleanupNow)) metrics.closed("expired");
        }
        entries.flush();
        if (lapsed.size() == CLOSE_BATCH_SIZE) return Result.MORE;
        var stale = entries.lockStaleWaiting(variantId, drop.isOnSale(cleanupNow),
                cleanupNow.minus(settings.heartbeatTimeout()), CLOSE_BATCH_SIZE);
        for (var entry : stale) {
            var now = clock.instant();
            entry.refresh(now, settings.heartbeatTimeout(), drop.isOnSale(now));
            if (entry.getStatus() == DropQueueStatus.CLOSED) metrics.closed("heartbeat_or_sale");
        }
        entries.flush();
        if (stale.size() == CLOSE_BATCH_SIZE) return Result.MORE;
        if (allowance == 0) return Result.DONE;
        int admitted = 0;
        while (admitted < allowance) {
            var batch = entries.lockWaiting(variantId, allowance - admitted);
            if (batch.isEmpty()) break;
            for (var entry : batch) {
                var now = clock.instant();
                entry.refresh(now, settings.heartbeatTimeout(), drop.isOnSale(now));
                if (entry.getStatus() == DropQueueStatus.WAITING) {
                    entry.admit(now, settings.admissionTtl());
                    metrics.admitted();
                    admitted++;
                } else {
                    metrics.closed("heartbeat_or_sale");
                }
            }
            entries.flush();
        }
        return Result.DONE;
    }
}
