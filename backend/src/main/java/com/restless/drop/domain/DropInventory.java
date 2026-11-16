package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "drop_inventories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropInventory {
    @Id @Column(name = "variant_id")
    private Long variantId;
    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;
    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;
    @Version
    private long version;

    public static DropInventory initial(Long variantId, int quantity) {
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        DropInventory inventory = new DropInventory();
        inventory.variantId = variantId;
        inventory.totalQuantity = quantity;
        inventory.availableQuantity = quantity;
        return inventory;
    }

    public boolean isSoldOut() {
        return availableQuantity == 0;
    }
}
