package com.restless.drop.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DropQueueGateRepository {
    public enum LockResult { LOCKED, BUSY, MISSING }
    private final JdbcTemplate jdbc;

    public void create(Long variantId) {
        jdbc.update("INSERT INTO drop_queue_gates (variant_id) VALUES (?)", variantId);
    }

    public void createIfMissing(Long variantId) {
        jdbc.update("INSERT IGNORE INTO drop_queue_gates (variant_id) VALUES (?)", variantId);
    }

    /** Transaction-scoped row lock; the existence check is a nonlocking consistent read. */
    public LockResult tryLock(Long variantId) {
        if (!jdbc.queryForList("SELECT variant_id FROM drop_queue_gates WHERE variant_id = ? FOR UPDATE SKIP LOCKED",
                Long.class, variantId).isEmpty()) return LockResult.LOCKED;
        boolean exists = !jdbc.queryForList("SELECT 1 FROM drop_queue_gates WHERE variant_id = ?",
                Integer.class, variantId).isEmpty();
        return exists ? LockResult.BUSY : LockResult.MISSING;
    }

    /** Gate lock result and the admission rate window read under the same lock (#551). */
    public record RateGate(LockResult result, long windowSecond, int windowAdmits) { }

    /** Same lock as {@link #tryLock}; also reads the shared admission rate window of the locked row. */
    public RateGate tryLockRate(Long variantId) {
        var locked = jdbc.query(
                "SELECT rate_window_second, rate_window_admits FROM drop_queue_gates WHERE variant_id = ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new RateGate(LockResult.LOCKED, rs.getLong(1), rs.getInt(2)), variantId);
        if (!locked.isEmpty()) return locked.getFirst();
        boolean exists = !jdbc.queryForList("SELECT 1 FROM drop_queue_gates WHERE variant_id = ?",
                Integer.class, variantId).isEmpty();
        return new RateGate(exists ? LockResult.BUSY : LockResult.MISSING, 0, 0);
    }

    /** Call only from the transaction that holds the gate row lock. The caller already decided the window. */
    public void recordAdmits(Long variantId, long windowSecond, int windowAdmits) {
        jdbc.update("UPDATE drop_queue_gates SET rate_window_second = ?, rate_window_admits = ? WHERE variant_id = ?",
                windowSecond, windowAdmits, variantId);
    }
}
