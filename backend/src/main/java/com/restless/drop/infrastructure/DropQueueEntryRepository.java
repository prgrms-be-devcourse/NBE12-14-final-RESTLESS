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

    /**
     * Locks only the member's latest row, by primary key (#516). A locking range read on (drop_id, user_id) also
     * locked the neighbouring member's row when this member had none, so a first entry waited for the neighbour's
     * uncommitted entry. Callers run in READ COMMITTED and hold the member's purchase slot lock, so the plain read
     * sees the member's latest committed row and no newer row can be inserted before the lock. The locked read
     * returns the row's latest committed state.
     */
    default Optional<DropQueueEntry> lockLatest(Long dropId, Long userId) {
        return latestId(dropId, userId).flatMap(this::lockById);
    }

    @Query(value = "SELECT id FROM drop_queue_entries WHERE drop_id=:dropId AND user_id=:userId ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<Long> latestId(Long dropId, Long userId);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE id=:id FOR UPDATE", nativeQuery = true)
    Optional<DropQueueEntry> lockById(Long id);

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

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='ADMITTED' AND admitted_until <= :now ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockExpiredAdmitted(Long variantId, Instant now, int batch);

    @Query(value = "SELECT * FROM drop_queue_entries WHERE variant_id=:variantId AND status='WAITING' AND (:onSale = false OR last_seen_at < :cutoff) ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<DropQueueEntry> lockStaleWaiting(Long variantId, boolean onSale, Instant cutoff, int batch);

    @Query(value = "SELECT COUNT(*) FROM drop_queue_entries WHERE status='WAITING' AND last_seen_at >= :cutoff", nativeQuery = true)
    long waitingCount(Instant cutoff);
}
