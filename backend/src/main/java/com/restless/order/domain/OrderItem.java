package com.restless.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/** 주문 시점의 상품명, 옵션, 가격 스냅샷. 원본이 바뀌어도 변하지 않는다 (INV-13). */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {
    public static final int MAX_ITEM_TYPE_LENGTH = 30;
    public static final int MAX_ITEM_NAME_LENGTH = 200;
    public static final int MAX_OPTION_NAME_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;

    @Column(length = MAX_ITEM_TYPE_LENGTH)
    private String itemType;

    private Long itemRefId;

    @Column(name = "item_name_snapshot", length = MAX_ITEM_NAME_LENGTH)
    private String itemName;

    @Column(name = "option_name_snapshot", length = MAX_OPTION_NAME_LENGTH)
    private String optionName;

    @Column(name = "unit_price_snapshot")
    private long unitPrice;

    private int quantity;

    /** @param optionName 옵션이 없으면 빈 문자열 */
    public static OrderItem snapshot(Long orderId, String itemType, Long itemRefId, String itemName,
                                     String optionName, long unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.orderId = Objects.requireNonNull(orderId, "orderId");
        item.itemType = requireText(itemType, "itemType", MAX_ITEM_TYPE_LENGTH);
        item.itemRefId = Objects.requireNonNull(itemRefId, "itemRefId");
        item.itemName = requireText(itemName, "itemName", MAX_ITEM_NAME_LENGTH);
        item.optionName = Objects.requireNonNull(optionName, "optionName");
        if (optionName.length() > MAX_OPTION_NAME_LENGTH) {
            throw new IllegalArgumentException("optionName must be at most " + MAX_OPTION_NAME_LENGTH + " characters");
        }
        if (unitPrice < 0) throw new IllegalArgumentException("unitPrice must be nonnegative");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        return item;
    }

    /** @throws ArithmeticException 금액이 long 범위를 넘을 때 */
    public long lineAmount() {
        return Math.multiplyExact(unitPrice, quantity);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
