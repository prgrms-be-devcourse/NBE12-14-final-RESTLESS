package com.restless.order.application;

import java.time.Instant;
import java.util.Objects;

/**
 * 주문의 결제 대상. 결제 준비 {@code POST /api/payments}에 그대로 넘긴다.
 *
 * @param expiresAt 결제 기한. 모르면 null
 */
public record OrderPayable(String payableType, Long payableId, Instant expiresAt) {
    public OrderPayable {
        Objects.requireNonNull(payableType, "payableType");
        Objects.requireNonNull(payableId, "payableId");
    }
}
