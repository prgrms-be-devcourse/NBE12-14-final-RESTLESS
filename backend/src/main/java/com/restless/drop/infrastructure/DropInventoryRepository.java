package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DropInventoryRepository extends JpaRepository<DropInventory, Long> {
    /** EXP-01 comparison point: the only inventory hold SQL (INV-01, INV-02). */
    @Modifying
    @Query(value = """
            UPDATE drop_inventories
            SET available_quantity = available_quantity - 1,
                reserved_quantity = reserved_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND available_quantity > 0
            """, nativeQuery = true)
    int holdOne(Long variantId);

    @Modifying
    @Query(value = """
            UPDATE drop_inventories SET reserved_quantity = reserved_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND reserved_quantity > 0
            """, nativeQuery = true)
    int release(Long variantId);

    @Modifying
    @Query(value = """
            UPDATE drop_inventories SET reserved_quantity = reserved_quantity - 1,
                sold_quantity = sold_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND reserved_quantity > 0
            """, nativeQuery = true)
    int confirm(Long variantId);

    @Modifying
    @Query(value = """
            UPDATE drop_inventories SET sold_quantity = sold_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = :variantId AND sold_quantity > 0
            """, nativeQuery = true)
    int restore(Long variantId);
}
