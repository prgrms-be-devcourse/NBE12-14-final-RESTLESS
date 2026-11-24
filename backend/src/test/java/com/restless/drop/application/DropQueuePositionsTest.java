package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropQueuePositionsTest {
    private final DropQueueEntryRepository entries = mock();
    private final DropQueueIntegrationTest.MutableClock clock = new DropQueueIntegrationTest.MutableClock();
    private final Duration timeout = Duration.ofSeconds(30);
    private final DropQueuePositions positions = new DropQueuePositions(entries,
            new DropQueueSettings(timeout, Duration.ofMinutes(5), 100), clock);
    private final long sku = 7;

    @Test
    void countsIdsInsideSnapshotAndOnlyQueriesBeyondLastId() {
        var cutoff = clock.instant().minus(timeout);
        when(entries.waitingIds(sku, cutoff)).thenReturn(List.of(4L, 7L, 11L));
        var snapshot = positions.snapshot(sku);
        assertThat(positions.position(sku, 4, clock.instant(), snapshot)).isOne();
        assertThat(positions.position(sku, 11, clock.instant(), snapshot)).isEqualTo(3);
        assertThat(positions.position(sku, 9, clock.instant(), snapshot)).isEqualTo(2);
        assertThat(positions.position(sku, 2, clock.instant(), snapshot)).isOne();
        when(entries.countWaitingTail(sku, 11, 15, cutoff)).thenReturn(2L);
        assertThat(positions.position(sku, 15, clock.instant(), snapshot)).isEqualTo(5);
        verify(entries).waitingIds(sku, cutoff);
        verify(entries).countWaitingTail(sku, 11, 15, cutoff);
        verifyNoMoreInteractions(entries);
    }

    @Test
    void emptySnapshotIsSharedUntilNextEpochSecondAndUsesActualHeartbeatCutoff() {
        clock.now = Instant.ofEpochMilli(999);
        var cutoff = clock.now.minus(timeout);
        when(entries.waitingIds(sku, cutoff)).thenReturn(List.of());
        when(entries.countWaitingTail(sku, 0, 42, cutoff)).thenReturn(1L);
        var snapshot = positions.snapshot(sku);
        assertThat(positions.snapshot(sku)).isSameAs(snapshot);
        assertThat(positions.position(sku, 42, clock.now, snapshot)).isOne();
        clock.now = Instant.ofEpochSecond(1);
        when(entries.waitingIds(sku, clock.now.minus(timeout))).thenReturn(List.of(42L));
        var next = positions.snapshot(sku);
        assertThat(positions.position(sku, 42, clock.now, next)).isOne();
        verify(entries).waitingIds(sku, cutoff);
        verify(entries).waitingIds(sku, clock.now.minus(timeout));
    }

    @Test
    void failedReadCanRetryAndIdleSnapshotsAreReleasedWithoutDbAccess() {
        var cutoff = clock.now.minus(timeout);
        when(entries.waitingIds(sku, cutoff)).thenThrow(new IllegalStateException()).thenReturn(List.of(8L));
        assertThatIllegalStateException().isThrownBy(() -> positions.snapshot(sku));
        var snapshot = positions.snapshot(sku);
        clock.now = Instant.ofEpochSecond(1);
        positions.discardExpired();
        verify(entries, times(2)).waitingIds(sku, cutoff);
        verifyNoMoreInteractions(entries);
        clock.now = Instant.EPOCH;
        assertThat(positions.snapshot(sku)).isNotSameAs(snapshot);
    }
}
