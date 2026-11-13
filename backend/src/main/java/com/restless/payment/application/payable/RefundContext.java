package com.restless.payment.application.payable;

import com.restless.payment.domain.PaymentRefundKind;

import java.util.Objects;

/**
 * PG 전액 취소가 확인된 결제 정보.
 *
 * @param paymentId 취소된 결제 시도 ({@code payments.id})
 * @param kind      손님 환불인지, 승인 거절에 따른 보상 취소인지
 */
public record RefundContext(Long paymentId, PaymentRefundKind kind) {
    public RefundContext {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(kind, "kind");
    }
}
