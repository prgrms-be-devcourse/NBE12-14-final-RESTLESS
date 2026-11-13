package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropPurchaseSlot;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DropPurchaseSlotRepository extends JpaRepository<DropPurchaseSlot, DropPurchaseSlot.Id> {
    // No-op on duplicate: serialize first-time slot creation without swallowing FK errors.
    @Modifying
    @Query(value = """
            INSERT INTO drop_purchase_slots (drop_id, user_id, updated_at)
            VALUES (:dropId, :userId, :now)
            ON DUPLICATE KEY UPDATE drop_id = drop_id
            """, nativeQuery = true)
    void ensureExists(Long dropId, Long userId, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DropPurchaseSlot s where s.id = :id")
    DropPurchaseSlot lockById(DropPurchaseSlot.Id id);
}
