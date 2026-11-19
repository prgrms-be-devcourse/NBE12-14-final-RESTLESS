package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import java.util.List;
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
