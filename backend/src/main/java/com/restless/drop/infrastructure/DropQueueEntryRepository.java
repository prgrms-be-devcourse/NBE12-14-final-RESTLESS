package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropQueueEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DropQueueEntryRepository extends JpaRepository<DropQueueEntry, Long> {
    @Query(value = "SELECT * FROM drop_queue_entries WHERE drop_id=:dropId AND user_id=:userId ORDER BY id DESC LIMIT 1 FOR UPDATE", nativeQuery = true)
    Optional<DropQueueEntry> lockLatest(Long dropId, Long userId);

    @Query(value = "SELECT DISTINCT variant_id FROM drop_queue_entries WHERE status IN ('WAITING','ADMITTED')", nativeQuery = true)
    List<Long> liveVariantIds();

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='WAITING' ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockWaiting(Long variantId, int batch);

    // Both reads use the #377 covering index for id order and heartbeat filtering.
    @Query(value = """
            SELECT id FROM drop_queue_entries USE INDEX (ix_drop_queue_sku_position)
            WHERE variant_id=:variantId AND status='WAITING' AND last_seen_at >= :cutoff
            ORDER BY id""", nativeQuery = true)
    List<Long> waitingIds(Long variantId, Instant cutoff);

    @Query(value = """
            SELECT COUNT(*) FROM drop_queue_entries USE INDEX (ix_drop_queue_sku_position)
            WHERE variant_id=:variantId AND status='WAITING' AND id > :last AND id <= :id
              AND last_seen_at >= :cutoff""", nativeQuery = true)
    long countWaitingTail(Long variantId, long last, long id, Instant cutoff);

    @Query(value = "SELECT COUNT(*) FROM drop_queue_entries WHERE variant_id=:variantId AND status='ADMITTED' AND admitted_until > :now", nativeQuery = true)
    long validGrantCount(Long variantId, Instant now);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status IN ('WAITING','ADMITTED') ORDER BY id LIMIT :batch FOR UPDATE", nativeQuery = true)
    List<DropQueueEntry> lockLive(Long variantId, int batch);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='WAITING' AND (:onSale = false OR last_seen_at < :cutoff) ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockStaleWaiting(Long variantId, boolean onSale, Instant cutoff, int batch);

    @Query(value = "SELECT COUNT(*) FROM drop_queue_entries WHERE status='WAITING' AND last_seen_at >= :cutoff", nativeQuery = true)
    long waitingCount(Instant cutoff);
}
