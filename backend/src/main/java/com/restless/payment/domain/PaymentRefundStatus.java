package com.restless.payment.domain;

public enum PaymentRefundStatus {
    REQUESTED, COMPLETED, UNKNOWN, FAILED;

    public boolean canTransitionTo(PaymentRefundStatus target) {
        return switch (this) {
            case REQUESTED -> target == COMPLETED || target == UNKNOWN || target == FAILED;
            case UNKNOWN -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
