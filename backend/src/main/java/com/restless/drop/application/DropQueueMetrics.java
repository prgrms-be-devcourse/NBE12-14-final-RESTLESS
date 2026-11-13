package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DropQueueMetrics {
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
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
