package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DropQueueMetrics {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DropQueueMetrics.class);
    private final MeterRegistry registry;
    public DropQueueMetrics(MeterRegistry registry, DropQueueEntryRepository entries,
                            DropQueueSettings settings, Clock clock) {
        this.registry = registry;
        registry.gauge("drop.queue.waiting", entries,
                repository -> repository.waitingCount(clock.instant().minus(settings.heartbeatTimeout())));
    }
    public void entered() { afterCommit(() -> registry.counter("drop.queue.entered").increment()); }
    public void admitted() { afterCommit(() -> registry.counter("drop.queue.admitted").increment()); }
    public void closed(String reason) {
        afterCommit(() -> registry.counter("drop.queue.closed", "reason", reason).increment());
    }
    public void unavailable() { afterCommit(() -> registry.counter("drop.queue.unavailable").increment()); }
    public void skipped() { afterCommit(() -> registry.counter("drop.queue.skipped").increment()); }
    public void gateMissing() { registry.counter("drop.queue.gate.missing").increment(); }
    public void failed() { registry.counter("drop.queue.failure").increment(); }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); }
                catch (RuntimeException error) {
                    log.error("Drop queue metric failed errorType={}", error.getClass().getName());
                    registry.counter("drop.queue.metric.failure").increment();
                }
            }
        });
    }
}
