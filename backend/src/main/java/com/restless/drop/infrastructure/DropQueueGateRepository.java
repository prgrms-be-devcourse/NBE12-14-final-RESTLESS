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
}
