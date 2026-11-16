package com.restless.order.domain;

/**
 * 공통 주문 상태 (domains/drop/state-machines.md 4절). 결제 시도 상태는 {@code payments}가 따로 가진다.
 *
 * <pre>
 * PENDING_PAYMENT → PAYMENT_CONFIRMING / CANCELLED
 * PAYMENT_CONFIRMING → PAID / PAYMENT_REVIEW / CANCELLED / PENDING_PAYMENT
 * PAYMENT_REVIEW → PAID / CANCELLED / PENDING_PAYMENT
 * PAID → REFUND_PENDING
 * REFUND_PENDING → REFUNDED / REFUND_REVIEW
 * REFUND_REVIEW → REFUNDED / PAID
 * </pre>
 *
 * <p>{@code → PENDING_PAYMENT}: 이번 결제 시도의 실패가 확정됐지만 결제 대상의 기한이 남아 다시 결제할 수 있다.
 */
public enum OrderStatus {
    PENDING_PAYMENT, PAYMENT_CONFIRMING, PAYMENT_REVIEW, PAID, CANCELLED, REFUND_PENDING, REFUND_REVIEW, REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) return false;
        return switch (this) {
            case PENDING_PAYMENT -> target == PAYMENT_CONFIRMING || target == CANCELLED;
            case PAYMENT_CONFIRMING -> target == PAID || target == PAYMENT_REVIEW || target == CANCELLED
                || target == PENDING_PAYMENT;
            case PAYMENT_REVIEW -> target == PAID || target == CANCELLED || target == PENDING_PAYMENT;
            case PAID -> target == REFUND_PENDING;
            case REFUND_PENDING -> target == REFUNDED || target == REFUND_REVIEW;
            case REFUND_REVIEW -> target == REFUNDED || target == PAID;
            case CANCELLED, REFUNDED -> false;
        };
    }
}
