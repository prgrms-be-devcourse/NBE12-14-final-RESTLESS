package com.restless.payment.application.payable;

import java.time.Instant;
import java.util.Objects;

/**
 * PG가 승인한 결제 정보. 결제 모듈이 PG 승인 금액과 결제 금액이 같은지 확인한 뒤에만 전달한다 (INV-08).
 *
 * @param approvedAt PG 승인 시각. 결제 기한 판정은 이 시각을 기준으로 한다
 */
public record ApprovedPayment(Long paymentId, String pgOrderId, String paymentKey, long amount, Instant approvedAt) {
    public ApprovedPayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (amount < 0) throw new IllegalArgumentException("amount must be nonnegative");
    }
}
