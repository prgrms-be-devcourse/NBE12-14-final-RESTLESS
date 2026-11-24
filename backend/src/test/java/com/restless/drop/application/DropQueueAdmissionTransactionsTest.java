package com.restless.drop.application;

import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropQueueAdmissionTransactionsTest {
    final DropQueueGateRepository gates = mock(DropQueueGateRepository.class);
    final DropQueueEntryRepository entries = mock(DropQueueEntryRepository.class);
    final DropVariantRepository variants = mock(DropVariantRepository.class);
    final DropRepository drops = mock(DropRepository.class);
    final DropInventoryRepository inventories = mock(DropInventoryRepository.class);
    final DropQueueMetrics metrics = mock(DropQueueMetrics.class);
    final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    final DropQueueAdmissionTransactions worker = new DropQueueAdmissionTransactions(gates, entries, variants,
            drops, inventories, new DropQueueSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5), 2),
            metrics, Clock.fixed(now, ZoneOffset.UTC));

    @org.junit.jupiter.api.BeforeEach
    void seedDrop() {
        var variant = mock(DropVariant.class);
        when(variant.getDropId()).thenReturn(2L);
        when(variants.findById(1L)).thenReturn(Optional.of(variant));
        var drop = Drop.draft(1L, "item", "description", "url", now.minusSeconds(60), now.plusSeconds(600), now);
        drop.publish();
        when(drops.findById(2L)).thenReturn(Optional.of(drop));
    }

    @Test
    void busyGateSkipsWithoutReadingInventoryOrEntries() {
        when(gates.tryLock(1L)).thenReturn(DropQueueGateRepository.LockResult.BUSY);
        assertThat(worker.runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.SKIPPED);
        verifyNoInteractions(entries, inventories, variants, drops);
        verify(metrics).skipped();
    }

    @Test
    void inv16CountsGrantsBeforeReadingInventoryAndSubtractsExistingGrants() {
        when(gates.tryLock(1L)).thenReturn(DropQueueGateRepository.LockResult.LOCKED);
        when(entries.validGrantCount(1L, now)).thenReturn(2L);
        when(inventories.available(1L)).thenReturn(2);
        assertThat(worker.runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
        var order = inOrder(entries, inventories);
        order.verify(entries).validGrantCount(1L, now);
        order.verify(inventories).available(1L);
        verify(entries, never()).lockWaiting(anyLong(), anyInt());
    }

    @Test
    void closureBatchDoesNotChangeConsumedEntryAndStopsAfterStockReturn() {
        when(gates.tryLock(1L)).thenReturn(DropQueueGateRepository.LockResult.LOCKED);
        when(inventories.available(1L)).thenReturn(0, 1);
        var batch = java.util.stream.LongStream.range(0, 100)
                .mapToObj(id -> DropQueueEntry.waiting(2L, id, 1L, now)).toList();
        when(entries.lockLive(1L, 100)).thenReturn(batch);
        assertThat(worker.runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.MORE);
        assertThat(batch).allMatch(e -> e.getStatus() == DropQueueStatus.UNAVAILABLE);
        when(entries.validGrantCount(1L, now)).thenReturn(1L);
        assertThat(worker.runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
        verify(entries, times(1)).lockLive(1L, 100);
        var consumed = DropQueueEntry.waiting(2L, 3L, 1L, now);
        consumed.admit(now, Duration.ofMinutes(5));
        consumed.consume(now);
        consumed.unavailable();
        assertThat(consumed.getStatus()).isEqualTo(DropQueueStatus.CONSUMED);
    }

    @Test
    void expiredWaitingIsSkippedWithoutSpendingAdmissionAllowance() {
        when(gates.tryLock(1L)).thenReturn(DropQueueGateRepository.LockResult.LOCKED);
        when(inventories.available(1L)).thenReturn(2);
        var variant = mock(DropVariant.class);
        when(variant.getDropId()).thenReturn(2L);
        when(variants.findById(1L)).thenReturn(Optional.of(variant));
        var drop = Drop.draft(1L, "item", "description", "url", now.minusSeconds(60), now.plusSeconds(600), now);
        drop.publish();
        when(drops.findById(2L)).thenReturn(Optional.of(drop));
        var stale = DropQueueEntry.waiting(2L, 10L, 1L, now.minusSeconds(31));
        var first = DropQueueEntry.waiting(2L, 11L, 1L, now);
        var second = DropQueueEntry.waiting(2L, 12L, 1L, now);
        when(entries.lockWaiting(1L, 2)).thenReturn(List.of(stale, first));
        when(entries.lockWaiting(1L, 1)).thenReturn(List.of(second));
        worker.runBatch(1L);
        assertThat(stale.getStatus()).isEqualTo(DropQueueStatus.CLOSED);
        assertThat(first.getStatus()).isEqualTo(DropQueueStatus.ADMITTED);
        assertThat(second.getStatus()).isEqualTo(DropQueueStatus.ADMITTED);
    }
}
