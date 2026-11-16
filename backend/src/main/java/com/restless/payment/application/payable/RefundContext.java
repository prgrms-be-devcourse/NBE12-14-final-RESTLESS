package com.restless.payment.application.payable;

import com.restless.payment.domain.PaymentRefundKind;

import java.util.Objects;

/**
 * 전액 환불 흐름의 결제 문맥. 요청·최종 거절·완료 콜백에 전달하며, 이 값 자체가 PG 취소 확정을 뜻하지는 않는다.
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
