package com.restless.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.util.Objects;

/** 주문 시점의 배송지 스냅샷. 회원 기본 배송지가 바뀌어도 변하지 않는다 (drop policy 9절). */
@Entity
@Table(name = "order_shipping_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderShippingSnapshot implements Persistable<Long> {
    public static final int MAX_RECIPIENT_LENGTH = 50;
    public static final int MAX_PHONE_LENGTH = 20;
    public static final int MAX_ADDRESS_LENGTH = 255;

    @Id
    private Long orderId;

    private String recipient;

    private String phone;

    private String address;

    /** PK를 직접 넣으므로 save()가 SELECT 후 merge하지 않고 바로 INSERT하게 한다. */
    @Transient
    private boolean newEntity = true;

    public static OrderShippingSnapshot of(Long orderId, String recipient, String phone, String address) {
        OrderShippingSnapshot snapshot = new OrderShippingSnapshot();
        snapshot.orderId = Objects.requireNonNull(orderId, "orderId");
        snapshot.recipient = requireText(recipient, "recipient", MAX_RECIPIENT_LENGTH);
        snapshot.phone = requireText(phone, "phone", MAX_PHONE_LENGTH);
        snapshot.address = requireText(address, "address", MAX_ADDRESS_LENGTH);
        return snapshot;
    }

    @Override
    public Long getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        newEntity = false;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
