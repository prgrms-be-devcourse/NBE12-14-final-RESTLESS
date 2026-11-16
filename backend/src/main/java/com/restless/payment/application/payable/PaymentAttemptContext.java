package com.restless.payment.application.payable;

import java.time.Instant;
import java.util.Objects;

/**
 * Handler에 전달하는 결제 시도 한 번의 식별 정보. {@code payments} 행 하나가 시도 하나다 (ADR-0003).
 * 최종 실패 후 다시 결제하면 새 {@code payments} 행과 새 {@code paymentId}가 생긴다.
 *
 * <p>도메인은 결과를 반영하기 전에 이 {@code paymentId}가 자기가 승인 시작을 받아들인 시도인지 확인한다.
 * 다른 시도의 결과로 현재 시도의 자원을 바꾸지 않는다.
 *
 * @param paymentId         {@code payments.id}. 승인 시도 ID로 쓴다
 * @param approvalStartedAt 결제 모듈이 승인 시작(READY → PROCESSING)을 기록한 서버 시각. 같은 시도의 모든 호출에서 같은 값이다.
 *                          승인을 시작하지 않고 닫힌 시도(READY → FAILED)는 null.
 *                          PG 승인 시각({@link ApprovedPayment#approvedAt()})과 다르다
 */
public record PaymentAttemptContext(Long paymentId, Instant approvalStartedAt) {
    public PaymentAttemptContext {
        Objects.requireNonNull(paymentId, "paymentId");
    }

    public static PaymentAttemptContext notStarted(Long paymentId) {
        return new PaymentAttemptContext(paymentId, null);
    }

    public boolean approvalStarted() {
        return approvalStartedAt != null;
    }
}
