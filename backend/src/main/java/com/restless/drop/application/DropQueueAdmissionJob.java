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
                // Each batch commits and releases its gate; returns reread stock before the next batch.
                while (transactions.runBatch(variantId) == DropQueueAdmissionTransactions.Result.MORE) { }
            } catch (RuntimeException error) {
                failure(variantId, error);
            }
        }
    }

    private void failure(Long variantId, RuntimeException error) {
        log.error("Drop admission failed variantId={} errorType={}", variantId, error.getClass().getName());
        metrics.failed();
    }
}
