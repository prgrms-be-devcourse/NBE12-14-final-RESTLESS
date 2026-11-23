package com.restless.credit.application;

import com.restless.credit.domain.CreditCharge;
import com.restless.credit.domain.CreditChargeStatus;
import com.restless.credit.infrastructure.CreditChargeRepository;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.PaymentResultHandler;
import com.restless.payment.application.payable.RefundContext;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.PaymentRefundKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 모듈이 부르는 크레딧 충전 결제 결과 반영 (#316, 설계 6절).
 *
 * <p>결제 모듈의 트랜잭션 안에서만 실행한다({@code MANDATORY}). 잠금 순서는 결제 행(결제 모듈) → 충전 주문 행
 * {@code FOR UPDATE} → 지갑 행이다. 경매 행은 잠그지 않는다. 승인된 결제는 기한과 상관없이 지급한다
 * (받을 자원이 한정돼 있지 않으므로 돈을 받고 거절하지 않는다). 실패·불명확은 아무것도 바꾸지 않는다.
 */
@Component
public class CreditChargePaymentResultHandler implements PaymentResultHandler {
    private static final Logger log = LoggerFactory.getLogger(CreditChargePaymentResultHandler.class);
    private final CreditChargeRepository charges;
    private final CreditWalletService wallets;
    private final Clock clock;
    private final MeterRegistry meters;

    public CreditChargePaymentResultHandler(CreditChargeRepository charges, CreditWalletService wallets,
            Clock clock, MeterRegistry meters) {
        this.charges = charges;
        this.wallets = wallets;
        this.clock = clock;
        this.meters = meters;
    }

    @Override public PayableType type() { return PayableType.CREDIT_CHARGE; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayableSnapshot> load(Long id) {
        return charges.findById(id).filter(c -> c.getStatus() == CreditChargeStatus.READY)
                .map(c -> new PayableSnapshot(c.getUserId(), c.getAmount(), orderName(c.getAmount()), c.getExpiresAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApprovalStarting(Long id, PaymentAttemptContext attempt) {
        Optional<CreditCharge> found = lock(id);
        if (found.isEmpty()) return ApprovalDecision.reject("CHARGE_NOT_READY");
        CreditCharge charge = found.get();
        if (charge.getStatus() != CreditChargeStatus.READY) return ApprovalDecision.reject("CHARGE_NOT_READY");
        return clock.instant().isAfter(charge.getExpiresAt())
                ? ApprovalDecision.reject("CHARGE_EXPIRED") : ApprovalDecision.accept();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApproved(Long id, ApprovedPayment payment) {
        Optional<CreditCharge> found = lock(id);
        if (found.isEmpty()) return ApprovalDecision.reject("CHARGE_NOT_READY");
        CreditCharge charge = found.get();
        if (charge.getStatus() == CreditChargeStatus.PAID) {
            return charge.getPaidPaymentId().equals(payment.paymentId())
                    ? ApprovalDecision.accept() : ApprovalDecision.reject("CHARGE_ALREADY_PAID");
        }
        if (payment.amount() != charge.getAmount()) {
            // 결제 모듈이 저장 금액과 다른 승인을 막지만, 받은 돈과 다른 금액을 지급하지 않도록 한 번 더 막는다 (INV-AU-17).
            log.error("CREDIT_CHARGE_AMOUNT_MISMATCH chargeId={} paymentId={} paid={} expected={}",
                    id, payment.paymentId(), payment.amount(), charge.getAmount());
            return ApprovalDecision.reject("CHARGE_AMOUNT_MISMATCH");
        }
        Instant now = clock.instant();
        wallets.charge(charge.getUserId(), charge.getId(), charge.getAmount(), now);
        charge.pay(payment.paymentId(), now);
        return ApprovalDecision.accept();
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void onFailed(Long id, PaymentAttemptContext attempt) { }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void onUnknown(Long id, PaymentAttemptContext attempt) { }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void onRefunded(Long id, RefundContext refund) {
        if (refund.kind() != PaymentRefundKind.COMPENSATION) {
            log.error("CREDIT_CHARGE_UNEXPECTED_REFUND chargeId={} paymentId={} kind={}", id,
                    refund.paymentId(), refund.kind());
            meters.counter("payment.result.handler.failure", "action", "refund", "stage", "credit_charge").increment();
        }
    }

    private Optional<CreditCharge> lock(Long id) { return charges.findByIdForUpdate(id); }

    private static String orderName(long amount) {
        return "크레딧 " + NumberFormat.getIntegerInstance(Locale.ROOT).format(amount);
    }
}
