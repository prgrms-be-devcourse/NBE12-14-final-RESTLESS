package com.restless.drop.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DropQueueGateRepository {
    private final JdbcTemplate jdbc;

    public void create(Long variantId) {
        jdbc.update("INSERT INTO drop_queue_gates (variant_id) VALUES (?)", variantId);
    }

    /** Transaction-scoped row lock, automatically released on commit or rollback. */
    public boolean tryLock(Long variantId) {
        return !jdbc.queryForList("SELECT variant_id FROM drop_queue_gates WHERE variant_id = ? FOR UPDATE SKIP LOCKED",
                Long.class, variantId).isEmpty();
    }
}
