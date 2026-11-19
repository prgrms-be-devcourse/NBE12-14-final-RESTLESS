package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("dropQueueAdmissionJob")
@RequiredArgsConstructor
public class DropQueueAdmissionJob {
    private static final Logger log = LoggerFactory.getLogger(DropQueueAdmissionJob.class);
    private final DropQueueEntryRepository entries;
    private final DropQueueAdmissionTransactions transactions;
    private final DropQueueMetrics metrics;
    private final DropQueueGateRepair repair;

    public void runOnce() {
        java.util.List<Long> variants;
        try {
            variants = entries.liveVariantIds();
        } catch (RuntimeException error) {
            failure(null, error);
            return;
        }
        for (var variantId : variants) {
            try {
                runSku(variantId);
            } catch (RuntimeException error) {
                failure(variantId, error);
            }
        }
    }

    private void runSku(Long variantId) {
        boolean repaired = false;
        while (true) {
            // A batch has completed before repair starts: no admission transaction is suspended.
            var result = transactions.runBatch(variantId);
            if (result == DropQueueAdmissionTransactions.Result.MISSING) {
                if (repaired) {
                    metrics.skipped();
                    return;
                }
                log.error("Drop queue gate missing variantId={}", variantId);
                metrics.gateMissing();
                repair.createIfMissing(variantId);
                repaired = true;
            } else if (result != DropQueueAdmissionTransactions.Result.MORE) {
                return;
            }
        }
    }

    private void failure(Long variantId, RuntimeException error) {
        log.error("Drop admission failed variantId={} errorType={}", variantId, error.getClass().getName());
        metrics.failed();
    }
}
