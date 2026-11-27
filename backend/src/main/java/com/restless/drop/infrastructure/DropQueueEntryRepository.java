package com.restless.drop.infrastructure;

import com.restless.drop.domain.Drop;
import com.restless.drop.domain.DropQueueEntry;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DropQueueEntryRepository extends JpaRepository<DropQueueEntry, Long> {
    interface Latest {
        DropQueueEntry getEntry();
        Drop getSale();
    }

    @Query("""
            select e as entry, d as sale from DropQueueEntry e join Drop d on d.id = e.dropId
            where e.dropId = :dropId and e.userId = :userId order by e.id desc limit 1
            """)
    Optional<Latest> latest(Long dropId, Long userId);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE drop_queue_entries SET last_seen_at=:now
            WHERE id=:id AND status='WAITING' AND last_seen_at >= :cutoff AND last_seen_at < :dueBefore
            """, nativeQuery = true)
    int recordHeartbeat(long id, Instant now, Instant cutoff, Instant dueBefore);

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

    /**
     * Locks up to {@code batch} live rows of a sold-out SKU for closing: WAITING first, then ADMITTED (#519).
     * One query over both statuses had to sort the two index ranges by id, so it read and locked every live row of
     * the SKU to return one batch, and heartbeats on rows outside the batch waited for the closing transaction.
     * Each query below reads one status in index order and stops at its limit.
     */
    default List<DropQueueEntry> lockLive(Long variantId, int batch) {
        var rows = new java.util.ArrayList<>(lockLiveByStatus(variantId, "WAITING", batch));
        if (rows.size() < batch) rows.addAll(lockLiveByStatus(variantId, "ADMITTED", batch - rows.size()));
        return rows;
    }

    String LOCK_LIVE_BY_STATUS = """
            SELECT * FROM drop_queue_entries FORCE INDEX (ix_drop_queue_sku_order)
            WHERE variant_id=:variantId AND status=:status ORDER BY id LIMIT :batch FOR UPDATE""";

    @Query(value = LOCK_LIVE_BY_STATUS, nativeQuery = true)
    List<DropQueueEntry> lockLiveByStatus(Long variantId, String status, int batch);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='ADMITTED' AND admitted_until <= :now ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockExpiredAdmitted(Long variantId, Instant now, int batch);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='WAITING' AND (:onSale = false OR last_seen_at < :cutoff) ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockStaleWaiting(Long variantId, boolean onSale, Instant cutoff, int batch);

    @Query(value = "SELECT COUNT(*) FROM drop_queue_entries WHERE status='WAITING' AND last_seen_at >= :cutoff", nativeQuery = true)
    long waitingCount(Instant cutoff);
}
