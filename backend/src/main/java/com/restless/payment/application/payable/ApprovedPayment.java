package com.restless.payment.application.payable;

import java.time.Instant;
import java.util.Objects;

/**
 * PG가 승인한 결제 정보. 결제 모듈이 PG 승인 금액과 결제 금액이 같은지 확인한 뒤에만 전달한다 (INV-08).
 *
 * <p>결제 기한을 어느 시각으로 판정할지는 도메인 정책이 정한다. 결제 모듈은 두 시각을 모두 전달한다.
 * <ul>
 *   <li>드롭·티켓팅: 기한 안에 승인 시작을 받아들인 시도라면 승인이 늦게 확인되어도 확정한다.
 *       기한은 {@code onApprovalStarting}에서 이미 판정했다. {@code approvedAt}으로 기한을 다시 판정하지 않는다.</li>
 *   <li>팬미팅: {@code approvedAt <= 결제 기한}으로 판정한다 (domains/fanmeeting/policy.md).</li>
 * </ul>
 *
 * @param paymentId         승인된 결제 시도 ({@code payments.id})
 * @param approvalStartedAt 결제 모듈이 승인 시작을 기록한 서버 시각 ({@link PaymentAttemptContext#approvalStartedAt()}와 같은 값)
 * @param approvedAt        PG 승인 시각
 */
public record ApprovedPayment(Long paymentId, String pgOrderId, String paymentKey, long amount,
                              Instant approvalStartedAt, Instant approvedAt) {
    public ApprovedPayment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(pgOrderId, "pgOrderId");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(approvalStartedAt, "approvalStartedAt");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (amount < 0) throw new IllegalArgumentException("amount must be nonnegative");
    }

    public PaymentAttemptContext attempt() {
        return new PaymentAttemptContext(paymentId, approvalStartedAt);
    }
}
