package com.restless.drop.application;

import com.restless.drop.domain.*;
import com.restless.drop.infrastructure.*;
import com.restless.drop.infrastructure.DropQueueGateRepository.LockResult;
import com.restless.drop.infrastructure.DropQueueGateRepository.RateGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #551: the drop admission cap per second is split across the drop's SKUs, only lowers the INV-16 allowance,
 * and shares one window per SKU on the gate row.
 */
class DropQueueAdmissionRateTest {
    final DropQueueGateRepository gates = mock(DropQueueGateRepository.class);
    final DropQueueEntryRepository entries = mock(DropQueueEntryRepository.class);
    final DropVariantRepository variants = mock(DropVariantRepository.class);
    final DropRepository drops = mock(DropRepository.class);
    final DropInventoryRepository inventories = mock(DropInventoryRepository.class);
    final DropQueueMetrics metrics = mock(DropQueueMetrics.class);
    final Instant now = Instant.parse("2030-01-01T00:00:00.400Z");
    final long second = now.getEpochSecond();

    DropQueueAdmissionTransactions worker(int perDrop) {
        return new DropQueueAdmissionTransactions(gates, entries, variants, drops, inventories,
                new DropQueueSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5), 200),
                metrics, Clock.fixed(now, ZoneOffset.UTC), new DropQueueAdmissionRate(perDrop));
    }

    @BeforeEach
    void seed() {
        var variant = mock(DropVariant.class);
        when(variant.getDropId()).thenReturn(2L);
        when(variants.findById(1L)).thenReturn(Optional.of(variant));
        when(variants.countByDropId(2L)).thenReturn(1L);
        var drop = Drop.draft(1L, 1L, "item", "description", "url", now.minusSeconds(60), now.plusSeconds(600), now);
        drop.publish();
        when(drops.findById(2L)).thenReturn(Optional.of(drop));
        when(inventories.available(1L)).thenReturn(1000);
        when(entries.validGrantCount(1L, now)).thenReturn(0L);
        when(entries.lockWaiting(eq(1L), anyInt())).thenAnswer(call -> waiting(call.getArgument(1)));
    }

    List<DropQueueEntry> waiting(int n) {
        return LongStream.range(0, n).mapToObj(id -> DropQueueEntry.waiting(2L, 100 + id, 1L, now)).toList();
    }

    @Test
    void singleSkuDropGetsTheWholeDropRate() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, 0, 0));
        worker(120).runBatch(1L);
        verify(entries).lockWaiting(1L, 120);
        verify(gates).recordAdmits(1L, second, 120);
    }

    @Test
    void threeSkuDropSplitsTheDropRatePerSku() {
        when(variants.countByDropId(2L)).thenReturn(3L);
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, 0, 0));
        worker(120).runBatch(1L);
        verify(entries).lockWaiting(1L, 40);
        verify(gates).recordAdmits(1L, second, 40);
    }

    @Test
    void perSkuShareRoundsUp() {
        var rate = new DropQueueAdmissionRate(100);
        assertThat(rate.perSku(1)).isEqualTo(100);
        assertThat(rate.perSku(3)).isEqualTo(34);
        assertThat(rate.perSku(7)).isEqualTo(15);
        assertThat(new DropQueueAdmissionRate(1).perSku(3)).isEqualTo(1);
        assertThat(rate.perSku(0)).as("never divides by zero").isEqualTo(100);
    }

    @Test
    void sameSecondWindowAdmitsOnlyTheRemainder() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second, 7));
        assertThat(worker(10).runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
        verify(entries).lockWaiting(1L, 3);
        verify(metrics, times(3)).admitted();
        verify(gates).recordAdmits(1L, second, 10);
        verify(gates, never()).tryLock(anyLong());
    }

    @Test
    void olderWindowStartsANewWindowAtThisSecond() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second - 1, 10));
        worker(10).runBatch(1L);
        verify(entries).lockWaiting(1L, 10);
        verify(gates).recordAdmits(1L, second, 10);
    }

    @Test
    void capNeverRaisesTheInv16Allowance() {
        when(inventories.available(1L)).thenReturn(50);
        when(entries.validGrantCount(1L, now)).thenReturn(48L);
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, 0, 0));
        worker(10).runBatch(1L);
        verify(entries).lockWaiting(1L, 2);
        verify(gates).recordAdmits(1L, second, 2);
    }

    @Test
    void capNeverRaisesTheBatchLimit() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, 0, 0));
        worker(500).runBatch(1L);
        verify(entries).lockWaiting(1L, 200);
    }

    @Test
    void windowAheadOfThisServersClockStaysFullAndCleanupStillRuns() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second + 1, 10));
        assertThat(worker(10).runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
        verify(entries).lockExpiredAdmitted(eq(1L), eq(now), eq(100));
        verify(entries).lockStaleWaiting(eq(1L), anyBoolean(), any(), eq(100));
        verify(entries, never()).lockWaiting(anyLong(), anyInt());
        verify(gates, never()).recordAdmits(anyLong(), anyLong(), anyInt());
    }

    @Test
    void windowAheadWithRoomKeepsItsSecond() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second + 1, 4));
        worker(10).runBatch(1L);
        verify(entries).lockWaiting(1L, 6);
        verify(gates).recordAdmits(1L, second + 1, 10);
    }

    @Test
    void windowFarAheadOfThisServersClockIsTreatedAsStale() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second + 3, 10));
        worker(10).runBatch(1L);
        verify(entries).lockWaiting(1L, 10);
        verify(gates).recordAdmits(1L, second, 10);
    }

    @Test
    void soldOutClosingRunsEvenWhenTheWindowIsFull() {
        when(inventories.available(1L)).thenReturn(0);
        when(entries.lockLive(1L, DropQueueAdmissionTransactions.CLOSE_BATCH_SIZE)).thenReturn(waiting(3));
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second, 10));
        assertThat(worker(10).runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.DONE);
        verify(metrics, times(3)).unavailable();
        verify(gates, never()).recordAdmits(anyLong(), anyLong(), anyInt());
    }

    @Test
    void busyAndMissingGateBehaveAsWithoutCap() {
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.BUSY, 0, 0));
        assertThat(worker(10).runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.SKIPPED);
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.MISSING, 0, 0));
        assertThat(worker(10).runBatch(1L)).isEqualTo(DropQueueAdmissionTransactions.Result.MISSING);
        verifyNoInteractions(entries, inventories);
    }

    @Test
    void nothingAdmittedLeavesTheWindowUntouched() {
        when(entries.lockWaiting(eq(1L), anyInt())).thenReturn(List.of());
        when(gates.tryLockRate(1L)).thenReturn(new RateGate(LockResult.LOCKED, second, 1));
        worker(10).runBatch(1L);
        verify(gates, never()).recordAdmits(anyLong(), anyLong(), anyInt());
    }

    @Test
    void capOffUsesThePlainLockAndNeverWritesTheWindow() {
        when(inventories.available(1L)).thenReturn(50);
        when(gates.tryLock(1L)).thenReturn(LockResult.LOCKED);
        worker(0).runBatch(1L);
        verify(entries).lockWaiting(1L, 50);
        verify(gates, never()).tryLockRate(anyLong());
        verify(gates, never()).recordAdmits(anyLong(), anyLong(), anyInt());
        verify(variants, never()).countByDropId(anyLong());
    }

    @Test
    void negativeRateIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DropQueueAdmissionRate(-1));
    }
}
