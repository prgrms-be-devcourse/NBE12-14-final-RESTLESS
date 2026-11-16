package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "drop_variants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropVariant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "drop_id", nullable = false)
    private Long dropId;
    @Column(name = "sku_code", nullable = false, length = 50)
    private String skuCode;
    @Column(name = "option_name", nullable = false, length = 100)
    private String optionName;
    @Column(name = "price_amount", nullable = false)
    private long priceAmount;
    @Column(name = "is_active", nullable = false)
    private boolean active;

    public static DropVariant create(Long dropId, String skuCode, String optionName, long priceAmount) {
        DropVariant variant = new DropVariant();
        variant.dropId = dropId;
        variant.skuCode = skuCode;
        variant.optionName = optionName;
        variant.priceAmount = priceAmount;
        variant.active = true;
        return variant;
    }
}
