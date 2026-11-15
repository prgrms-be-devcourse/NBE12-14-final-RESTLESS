package com.restless.ticketing.application;

import com.restless.payment.application.payable.*;
import com.restless.payment.domain.PayableType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 공통 결제의 (TICKET, 예약 ID) callback을 전달한다. 트랜잭션 경계는 내부 서비스가 강제한다. */
@Component
@RequiredArgsConstructor
public class TicketPaymentResultHandler implements PaymentResultHandler {
    private final TicketPaymentService payments;

    @Override
    public PayableType type() {
        return PayableType.TICKET;
    }

    @Override
    public Optional<PayableSnapshot> load(Long payableId) {
        return payments.load(payableId);
    }

    @Override
    public ApprovalDecision onApprovalStarting(Long payableId, PaymentAttemptContext attempt) {
        return payments.onApprovalStarting(payableId, attempt);
    }

    @Override
    public ApprovalDecision onApproved(Long payableId, ApprovedPayment payment) {
        return payments.onApproved(payableId, payment);
    }

    @Override
    public void onFailed(Long payableId, PaymentAttemptContext attempt) {
        payments.onFailed(payableId, attempt);
    }

    @Override
    public void onUnknown(Long payableId, PaymentAttemptContext attempt) {
        payments.onUnknown(payableId, attempt);
    }

    @Override
    public void onRefunded(Long payableId, RefundContext refund) {
        payments.onRefunded(payableId, refund);
    }
    // 정상 환불 요청은 공통 계약의 REFUND_NOT_SUPPORTED, 거절 통지는 기본 no-op을 유지한다.
}
