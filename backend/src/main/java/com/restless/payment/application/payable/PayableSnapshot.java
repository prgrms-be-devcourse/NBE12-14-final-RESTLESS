package com.restless.payment.application.payable;

import java.time.Instant;
import java.util.Objects;

/**
 * 결제 생성 시점의 결제 대상 정보. 금액은 서버에 저장된 주문 스냅샷 금액이어야 한다 (INV-08).
 *
 * @param ownerUserId 결제 대상의 소유 회원. 결제 모듈이 요청 회원과 비교한다
 * @param amount      결제할 금액 (원)
 * @param orderName   토스 결제창에 표시할 상품명 (최대 100자)
 * @param expiresAt   이 시각 뒤에는 승인을 시작할 수 없다. 기한이 없으면 null
 */
public record PayableSnapshot(Long ownerUserId, long amount, String orderName, Instant expiresAt) {
    public static final int MAX_ORDER_NAME_LENGTH = 100;

    public PayableSnapshot {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        if (amount < 0) throw new IllegalArgumentException("amount must be nonnegative");
        if (orderName == null || orderName.isBlank()) throw new IllegalArgumentException("orderName is required");
        if (orderName.length() > MAX_ORDER_NAME_LENGTH) {
            throw new IllegalArgumentException("orderName must be at most " + MAX_ORDER_NAME_LENGTH + " characters");
        }
    }
}
