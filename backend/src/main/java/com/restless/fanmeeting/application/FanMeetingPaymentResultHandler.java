package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.PaymentResultHandler;
import com.restless.payment.application.payable.RefundContext;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.PaymentRefundKind;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 모듈이 부르는 팬미팅 결제 결과 반영 (transactions.md 7절, state-machines.md 4절).
 *
 * <p>결제 모듈의 트랜잭션 안에서만 실행한다({@code MANDATORY}). 결제 모듈이 결제 행을 먼저 잠그고 부르므로
 * 잠금 순서는 결제 행 → Event {@code FOR SHARE} → Order {@code FOR UPDATE} → Selection {@code FOR UPDATE}다.
 * 판정에 쓰는 현재 시각은 잠근 뒤에 읽는다. 업무상 받을 수 없는 결과는 reject로 돌려주고, 결제 모듈이 보상 취소한다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingPaymentResultHandler implements PaymentResultHandler {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingPaymentResultHandler.class);

    private final FanMeetingEventRepository events;
    private final FanMeetingOrderRepository orders;
    private final FanMeetingSelectionRepository selections;
    private final Clock clock;

    @Override
    public PayableType type() {
        return PayableType.FAN_MEETING;
    }

    /** 결제 준비. 결제 대기 주문이면 금액·기한을 알려 주고 결제 준비 요청 시각을 기록한다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayableSnapshot> load(Long orderId) {
        return lock(orderId)
                .filter(locked -> locked.event().getStatus() == FanMeetingStatus.PAYMENT_IN_PROGRESS)
                .filter(locked -> locked.order().isPendingPayment())
                .map(locked -> {
                    FanMeetingOrder order = locked.order();
                    order.recordAttemptRequested(clock.instant());
                    return new PayableSnapshot(order.getUserId(), order.getAmount(),
                            orderName(locked.event().getTitle()), order.paymentDeadline());
                });
    }

    /** 승인 시작. 잠근 뒤의 현재 시각이 기한 전이어야 받아들인다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApprovalStarting(Long orderId, PaymentAttemptContext attempt) {
        Optional<Locked> locked = lock(orderId);
        if (locked.isEmpty() || !locked.get().order().startApproval(attempt.paymentId(), clock.instant())) {
            return ApprovalDecision.reject("FANMEETING_NOT_PAYABLE");
        }
        return ApprovalDecision.accept();
    }

    /** 승인. 현재 시도이고 금액이 같고 {@code approvedAt <= 기한}이면 확정한다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApproved(Long orderId, ApprovedPayment payment) {
        Optional<Locked> found = lock(orderId);
        if (found.isEmpty()) {
            return ApprovalDecision.reject("FANMEETING_ORDER_NOT_FOUND");
        }
        FanMeetingOrder order = found.get().order();
        FanMeetingSelection selection = found.get().selection();
        if (order.getStatus() == FanMeetingOrderStatus.PAID && order.isApprovedBy(payment.paymentId())) {
            return ApprovalDecision.accept();
        }
        if (!order.isAwaitingResult() || !order.isCurrentAttempt(payment.paymentId())) {
            log.error("FANMEETING_APPROVAL_NOT_CURRENT orderId={} paymentId={} status={}",
                    orderId, payment.paymentId(), order.getStatus());
            return ApprovalDecision.reject("FANMEETING_STALE_ATTEMPT");
        }
        Instant now = clock.instant();
        if (payment.amount() != order.getAmount()) {
            return refuse(order, selection, payment, now, "FANMEETING_AMOUNT_MISMATCH");
        }
        if (payment.approvedAt().isAfter(order.paymentDeadline())) {
            return refuse(order, selection, payment, now, "FANMEETING_LATE_APPROVAL");
        }
        order.pay(payment.paymentId(), payment.approvedAt(), now);
        selection.confirm(now);
        return ApprovalDecision.accept();
    }

    /** 이 시도의 미승인 확정. 기한이 남으면 다시 결제할 수 있고, 지났으면 주문·선발 EXPIRED. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onFailed(Long orderId, PaymentAttemptContext attempt) {
        Optional<Locked> found = lock(orderId);
        if (found.isEmpty()) {
            return;
        }
        FanMeetingOrder order = found.get().order();
        // 승인을 시작한 시도면 현재 시도여야 한다. 시작하지 않은 시도(결제창만 열고 기한 지남)는 결제 대기일 때만 본다.
        boolean applies = attempt.approvalStarted()
                ? order.isAwaitingResult() && order.isCurrentAttempt(attempt.paymentId())
                : order.isPendingPayment() && order.getPaymentId() == null;
        if (!applies) {
            return;
        }
        Instant now = clock.instant();
        if (order.failAttempt(now)) {
            found.get().selection().expire(now);
        }
    }

    /** 결과 모름: 보류로 자리를 지킨다. 최종 결과는 결제 모듈이 같은 시도로 다시 알려 준다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onUnknown(Long orderId, PaymentAttemptContext attempt) {
        lock(orderId)
                .map(Locked::order)
                .filter(order -> order.isCurrentAttempt(attempt.paymentId()))
                .ifPresent(order -> order.markReview(clock.instant()));
    }

    /** 보상 취소 완료: 환불 대기 → 환불됨. 손님 환불은 P0 범위 밖이라 오지 않는다. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefunded(Long orderId, RefundContext refund) {
        if (refund.kind() != PaymentRefundKind.COMPENSATION) {
            log.error("FANMEETING_UNEXPECTED_REFUND orderId={} paymentId={} kind={}",
                    orderId, refund.paymentId(), refund.kind());
            return;
        }
        boolean refunded = lock(orderId)
                .map(locked -> locked.order().completeRefund(refund.paymentId(), clock.instant()))
                .orElse(false);
        if (!refunded) {
            // 현재 시도가 아니어서 상태를 바꾸지 않고 거절한 승인(이중 결제 등)의 환불. 주문은 그대로 둔다.
            log.info("FANMEETING_COMPENSATION_WITHOUT_STATE_CHANGE orderId={} paymentId={}",
                    orderId, refund.paymentId());
        }
    }

    private ApprovalDecision refuse(FanMeetingOrder order, FanMeetingSelection selection, ApprovedPayment payment,
                                    Instant now, String reason) {
        order.markRefundPending(payment.paymentId(), now);
        selection.expire(now);
        log.warn("{} orderId={} paymentId={}", reason, order.getId(), payment.paymentId());
        return ApprovalDecision.reject(reason);
    }

    /** Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE. 주문이 없으면 empty. */
    private Optional<Locked> lock(Long orderId) {
        Optional<Long> eventId = orders.findEventIdById(orderId);
        if (eventId.isEmpty()) {
            return Optional.empty();
        }
        FanMeetingEvent event = events.findByIdForShare(eventId.get()).orElseThrow();
        FanMeetingOrder order = orders.findByIdForUpdate(orderId).orElseThrow();
        FanMeetingSelection selection = selections.findByIdForUpdate(order.getSelectionId()).orElseThrow();
        return Optional.of(new Locked(event, order, selection));
    }

    private static String orderName(String title) {
        return title.length() <= PayableSnapshot.MAX_ORDER_NAME_LENGTH
                ? title : title.substring(0, PayableSnapshot.MAX_ORDER_NAME_LENGTH);
    }

    private record Locked(FanMeetingEvent event, FanMeetingOrder order, FanMeetingSelection selection) {
    }
}
