package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class DropQueuePositions {
    private final DropQueueEntryRepository entries;
    private final DropQueueSettings settings;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    Snapshot snapshot(long variantId) {
        var cached = snapshots.get(variantId);
        if (cached != null && cached.second == clock.instant().getEpochSecond()) return cached;
        return snapshots.compute(variantId, (key, previous) -> {
            var now = clock.instant();
            if (previous != null && previous.second == now.getEpochSecond()) return previous;
            var ids = entries.waitingIds(variantId, now.minus(settings.heartbeatTimeout()))
                    .stream().mapToLong(Long::longValue).toArray();
            return new Snapshot(now.getEpochSecond(), ids);
        });
    }

    @Scheduled(fixedDelay = 1000)
    void discardExpired() {
        long second = clock.instant().getEpochSecond();
        snapshots.entrySet().removeIf(entry -> entry.getValue().second < second);
    }

    long position(long variantId, long id, Instant now, Snapshot snapshot) {
        var ids = snapshot.ids;
        long last = ids.length == 0 ? 0 : ids[ids.length - 1];
        if (id > last) {
            return ids.length + entries.countWaitingTail(variantId, last, id, now.minus(settings.heartbeatTimeout()));
        }
        int index = Arrays.binarySearch(ids, id);
        // An earlier IDENTITY insert can commit after a higher id was captured.
        // Count the captured ids below it plus the entry itself, like the former COUNT.
        return index >= 0 ? index + 1L : -index;
    }

    @RequiredArgsConstructor
    static final class Snapshot {
        private final long second;
        private final long[] ids;
    }
}
