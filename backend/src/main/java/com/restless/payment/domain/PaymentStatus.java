package com.restless.payment.domain;

public enum PaymentStatus {
    READY, PROCESSING, APPROVED, FAILED, UNKNOWN, CANCEL_PENDING, CANCELLED, CANCEL_UNKNOWN;

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) return false;
        return switch (this) {
            case READY -> target == PROCESSING || target == FAILED;
            case PROCESSING -> target == APPROVED || target == FAILED || target == UNKNOWN;
            case UNKNOWN -> target == APPROVED || target == FAILED;
            case APPROVED -> target == CANCEL_PENDING;
            case CANCEL_PENDING -> target == CANCELLED || target == CANCEL_UNKNOWN || target == APPROVED;
            case CANCEL_UNKNOWN -> target == CANCELLED || target == APPROVED;
            case FAILED, CANCELLED -> false;
        };
    }
}
