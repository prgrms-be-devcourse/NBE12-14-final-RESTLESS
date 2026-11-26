package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropInventory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * SKU head rows and their stock buckets (ADR-0005). Every stock write changes exactly one bucket and swaps two
 * quantities inside it, so the per-bucket CHECKs keep INV-01 and INV-02. No query reads the frozen quantity
 * columns of {@code drop_inventories}.
 */
// Bucket numbers are TINYINT, which native queries may return as Byte; callers take Number and use intValue().
public interface DropInventoryRepository extends JpaRepository<DropInventory, Long> {
    /**
     * SKU available = bucket sum, read in one statement without locks. Under READ_COMMITTED one statement is one
     * snapshot, so INV-16 (count first, then available) still holds.
     */
    @Query(value = """
            SELECT CAST(COALESCE(SUM(available_quantity), 0) AS SIGNED)
            FROM drop_inventory_buckets WHERE variant_id = :variantId
            """, nativeQuery = true)
    int available(Long variantId);

    /** Rows of [variant_id, available sum] for the given SKUs, one GROUP BY read. */
    @Query(value = """
            SELECT variant_id, CAST(SUM(available_quantity) AS SIGNED)
            FROM drop_inventory_buckets WHERE variant_id IN (:variantIds) GROUP BY variant_id
            """, nativeQuery = true)
    List<Object[]> availableByVariant(Collection<Long> variantIds);

    /**
     * One row [active SKU count, available sum over the drop's active SKUs], read in one statement without locks.
     * Used only to decide whether a whole drop is sold out (#481). It never decides a purchase.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT v.id), CAST(COALESCE(SUM(b.available_quantity), 0) AS SIGNED)
            FROM drop_variants v JOIN drop_inventory_buckets b ON b.variant_id = v.id
            WHERE v.drop_id = :dropId AND v.is_active = TRUE
            """, nativeQuery = true)
    List<Object[]> activeStockOfDrop(Long dropId);

    /** TX1 step 5. The head row is never updated after creation, so this read does not wait. */
    @Query(value = "SELECT bucket_count FROM drop_inventories WHERE variant_id = :variantId", nativeQuery = true)
    int bucketCount(Long variantId);

    @Modifying
    @Query(value = """
            INSERT INTO drop_inventory_buckets
                (variant_id, bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity)
            VALUES (:variantId, :bucketNo, :quantity, :quantity, 0, 0)
            """, nativeQuery = true)
    int insertBucket(Long variantId, int bucketNo, int quantity);

    /** TX1 step 6, never waits: the first unlocked stock bucket at or after {@code from}. Locks only that row. */
    @Query(value = """
            SELECT bucket_no FROM drop_inventory_buckets
            WHERE variant_id = :variantId AND bucket_no >= :from AND available_quantity > 0
            ORDER BY bucket_no LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Number> pickFrom(Long variantId, int from);

    /** TX1 step 6, second half: the same pick below {@code before}. */
    @Query(value = """
            SELECT bucket_no FROM drop_inventory_buckets
            WHERE variant_id = :variantId AND bucket_no < :before AND available_quantity > 0
            ORDER BY bucket_no LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Number> pickBefore(Long variantId, int before);

    /**
     * TX1 step 8: nonlocking read of buckets with committed stock, ordered from {@code from} and wrapping around.
     * The caller drops buckets it already waited on (step 9-1).
     */
    @Query(value = """
            SELECT bucket_no FROM drop_inventory_buckets
            WHERE variant_id = :variantId AND available_quantity > 0
            ORDER BY (bucket_no >= :from) DESC, bucket_no
            """, nativeQuery = true)
    List<Number> stockedBuckets(Long variantId, int from);

    /**
     * TX1 steps 7 and 9, the only inventory hold SQL (INV-01, INV-02, INV-03): one statement takes a unit from the
     * bucket and records that bucket on the reservation. Changed rows are 2 (Connector/J default, found rows), or
     * 1 with useAffectedRows when bucket_no was already equal; 0 means the bucket had no stock and nothing changed.
     */
    @Modifying
    @Query(value = """
            UPDATE drop_inventory_buckets ib JOIN drop_reservations r ON r.id = :reservationId
            SET ib.available_quantity = ib.available_quantity - 1, ib.reserved_quantity = ib.reserved_quantity + 1,
                ib.version = ib.version + 1, r.bucket_no = ib.bucket_no
            WHERE ib.variant_id = :variantId AND ib.bucket_no = :bucketNo AND ib.available_quantity > 0
            """, nativeQuery = true)
    int holdInBucket(Long reservationId, Long variantId, int bucketNo);

    @Modifying
    @Query(value = """
            UPDATE drop_inventory_buckets SET reserved_quantity = reserved_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND bucket_no = :bucketNo AND reserved_quantity > 0
            """, nativeQuery = true)
    int release(Long variantId, int bucketNo);

    @Modifying
    @Query(value = """
            UPDATE drop_inventory_buckets SET reserved_quantity = reserved_quantity - 1,
                sold_quantity = sold_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND bucket_no = :bucketNo AND reserved_quantity > 0
            """, nativeQuery = true)
    int confirm(Long variantId, int bucketNo);

    @Modifying
    @Query(value = """
            UPDATE drop_inventory_buckets SET sold_quantity = sold_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND bucket_no = :bucketNo AND sold_quantity > 0
            """, nativeQuery = true)
    int restore(Long variantId, int bucketNo);
}
