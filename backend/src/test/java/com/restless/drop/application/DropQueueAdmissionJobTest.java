package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import com.restless.support.RecordedLogs;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DropQueueAdmissionJobTest {
    @Test
    void busyDoesNotRepairOrEmitMissingMetric() {
        var entries = mock(DropQueueEntryRepository.class);
        var batches = mock(DropQueueAdmissionTransactions.class);
        var metrics = mock(DropQueueMetrics.class);
        var repair = mock(DropQueueGateRepair.class);
        when(entries.liveVariantIds()).thenReturn(List.of(10L));
        when(batches.runBatch(10L)).thenReturn(DropQueueAdmissionTransactions.Result.SKIPPED);
        new DropQueueAdmissionJob(entries, batches, metrics, repair).runOnce();
        verifyNoInteractions(repair, metrics);
        verify(batches, times(1)).runBatch(10L);
    }

    @Test
    void gateRepairLogsDistinguishRecoveryFromPersistentFailure() {
        var entries = mock(DropQueueEntryRepository.class);
        var batches = mock(DropQueueAdmissionTransactions.class);
        when(entries.liveVariantIds()).thenReturn(List.of(10L, 20L, 30L));
        var missing = DropQueueAdmissionTransactions.Result.MISSING;
        var done = DropQueueAdmissionTransactions.Result.DONE;
        when(batches.runBatch(10L)).thenReturn(missing, done);
        when(batches.runBatch(20L)).thenReturn(missing, missing);
        when(batches.runBatch(30L)).thenReturn(done);
        var job = new DropQueueAdmissionJob(entries, batches, mock(DropQueueMetrics.class),
                mock(DropQueueGateRepair.class));
        try (var logs = new RecordedLogs(DropQueueAdmissionJob.class)) {
            job.runOnce();
            assertThat(logs.entries()).containsExactly(
                    "WARN event=drop.queue.gate.repair_started variantId=10",
                    "WARN event=drop.queue.gate.repair_started variantId=20",
                    "ERROR event=drop.queue.gate.repair_unresolved variantId=20");
        }
    }

    @Test
    void missingRetriesOnceAndDoesNotLoopIfStillMissing() {
        var entries = mock(DropQueueEntryRepository.class);
        var batches = mock(DropQueueAdmissionTransactions.class);
        var metrics = mock(DropQueueMetrics.class);
        var repair = mock(DropQueueGateRepair.class);
        when(entries.liveVariantIds()).thenReturn(List.of(10L));
        when(batches.runBatch(10L)).thenReturn(DropQueueAdmissionTransactions.Result.MISSING);
        new DropQueueAdmissionJob(entries, batches, metrics, repair).runOnce();
        verify(repair, times(1)).createIfMissing(10L);
        verify(batches, times(2)).runBatch(10L);
        verify(metrics, times(1)).gateMissing();
        verify(metrics).skipped();
    }
}
