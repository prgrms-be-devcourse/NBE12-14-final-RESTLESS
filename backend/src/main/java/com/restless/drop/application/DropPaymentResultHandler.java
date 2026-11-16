package com.restless.drop.application;

import com.restless.drop.domain.DropReservation;
import com.restless.drop.domain.DropReservationStatus;
import com.restless.drop.infrastructure.DropRepository;
import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.order.application.OrderService;
import com.restless.order.domain.OrderStatus;
import com.restless.payment.application.payable.*;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.PaymentRefundKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Payment owns the transaction and PG I/O; this Handler owns only the drop state. */
@Component
@RequiredArgsConstructor
public class DropPaymentResultHandler implements PaymentResultHandler {
    private static final Logger log = LoggerFactory.getLogger(DropPaymentResultHandler.class);
    private final DropReservationRepository reservations;
    private final DropRepository drops;
    private final OrderService orders;
    private final DropReservationSettlement settlement;
    private final Clock clock;
    private final MeterRegistry meters;

    @Override
    public PayableType type() { return PayableType.DROP; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayableSnapshot> load(Long payableId) {
        return reservations.findByIdForUpdate(payableId)
                .filter(r -> r.getStatus() == DropReservationStatus.RESERVED)
                .map(r -> {
                    var order = orders.find(r.getOrderId()).orElseThrow(() -> new IllegalStateException("Drop order is missing"));
                    var title = drops.findById(r.getDropId()).orElseThrow().getTitle();
                    var name = title.substring(0, Math.min(title.length(), PayableSnapshot.MAX_ORDER_NAME_LENGTH));
                    return new PayableSnapshot(r.getUserId(), order.totalAmount(), name, r.getExpiresAt());
                });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApprovalStarting(Long payableId, PaymentAttemptContext attempt) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || attempt.approvalStartedAt() == null
                || !reservation.startApproval(attempt.paymentId(), clock.instant())) {
            return reject("starting", "DROP_NOT_PAYABLE");
        }
        orders.changeStatus(reservation.getOrderId(), OrderStatus.PAYMENT_CONFIRMING);
        count("starting", "accepted");
        return ApprovalDecision.accept();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApproved(Long payableId, ApprovedPayment payment) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        // INV-15: an unrelated attempt must never put the current purchase into compensation.
        if (reservation == null || !reservation.isCurrentPayment(payment.paymentId())) {
            return reject("approved", "DROP_STALE_ATTEMPT");
        }
        if (reservation.getStatus() == DropReservationStatus.CONFIRMED) {
            count("approved", "duplicate");
            return ApprovalDecision.accept();
        }
        if (!reservation.isApprovalPending()) return reject("approved", "DROP_INVALID_STATE");
        var now = clock.instant();
        var order = orders.find(reservation.getOrderId()).orElseThrow(() -> new IllegalStateException("Drop order is missing"));
        if (payment.amount() != order.totalAmount()) {
            reservation.compensate(payment.paymentId(), now);
            return reject("approved", "DROP_AMOUNT_MISMATCH");
        }
        reservation.confirm(payment.paymentId(), now);
        settlement.confirm(reservation);
        count("approved", "confirmed");
        return ApprovalDecision.accept();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onFailed(Long payableId, PaymentAttemptContext attempt) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || attempt.approvalStartedAt() == null
                || !reservation.isCurrentPayment(attempt.paymentId()) || !reservation.isApprovalPending()) {
            count("failed", "ignored");
            return;
        }
        var now = clock.instant();
        if (reservation.retryAfterFailure(attempt.paymentId(), now)) {
            orders.changeStatus(reservation.getOrderId(), OrderStatus.PENDING_PAYMENT);
            count("failed", "retryable");
        } else if (reservation.releaseAfterFailure(attempt.paymentId(), now)) {
            settlement.release(reservation);
            count("failed", "released");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onUnknown(Long payableId, PaymentAttemptContext attempt) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || !reservation.markUnknown(attempt.paymentId(), clock.instant())) {
            count("unknown", "ignored");
            return;
        }
        orders.changeStatus(reservation.getOrderId(), OrderStatus.PAYMENT_REVIEW);
        count("unknown", "protected");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onRefundRequesting(Long payableId, RefundContext refund) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || !reservation.isCurrentPayment(refund.paymentId())
                || refund.kind() != PaymentRefundKind.CUSTOMER_REFUND
                || reservation.getStatus() != DropReservationStatus.CONFIRMED) {
            return reject("refund_requesting", "DROP_NOT_REFUNDABLE");
        }
        var order = orders.find(reservation.getOrderId()).orElseThrow();
        if (order.status() == OrderStatus.REFUND_PENDING) {
            count("refund_requesting", "duplicate");
            return ApprovalDecision.accept();
        }
        // A final PG rejection leaves REFUND_REVIEW; a new request may retry the refund.
        if (order.status() == OrderStatus.REFUND_REVIEW) {
            orders.changeStatus(reservation.getOrderId(), OrderStatus.PAID);
        } else if (order.status() != OrderStatus.PAID) {
            return reject("refund_requesting", "DROP_NOT_REFUNDABLE");
        }
        orders.changeStatus(reservation.getOrderId(), OrderStatus.REFUND_PENDING);
        count("refund_requesting", "accepted");
        return ApprovalDecision.accept();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefundRejected(Long payableId, RefundContext refund) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || !reservation.isCurrentPayment(refund.paymentId())
                || refund.kind() != PaymentRefundKind.CUSTOMER_REFUND
                || reservation.getStatus() != DropReservationStatus.CONFIRMED) {
            count("refund_rejected", "ignored");
            return;
        }
        var order = orders.find(reservation.getOrderId()).orElseThrow();
        if (order.status() != OrderStatus.REFUND_PENDING) {
            count("refund_rejected", "ignored");
            return;
        }
        orders.changeStatus(reservation.getOrderId(), OrderStatus.REFUND_REVIEW);
        count("refund_rejected", "review");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefunded(Long payableId, RefundContext refund) {
        var reservation = reservations.findByIdForUpdate(payableId).orElse(null);
        if (reservation == null || !reservation.isCurrentPayment(refund.paymentId())) {
            anomaly(payableId);
            return;
        }
        var now = clock.instant();
        if (refund.kind() == PaymentRefundKind.CUSTOMER_REFUND) {
            if (reservation.getStatus() == DropReservationStatus.RESTORED) {
                count("refunded", "duplicate");
                return;
            }
            if (reservation.getStatus() != DropReservationStatus.CONFIRMED) {
                anomaly(payableId);
                return;
            }
            var order = orders.find(reservation.getOrderId()).orElseThrow();
            if (order.status() != OrderStatus.REFUND_PENDING && order.status() != OrderStatus.REFUND_REVIEW) {
                anomaly(payableId);
                return;
            }
            reservation.restore(refund.paymentId(), now);
            settlement.restore(reservation);
            count("refunded", "restored");
        } else if (refund.kind() == PaymentRefundKind.COMPENSATION) {
            if (reservation.getStatus() == DropReservationStatus.RELEASED) {
                count("refunded", "duplicate");
                return;
            }
            if (!reservation.completeCompensation(refund.paymentId(), now)) {
                anomaly(payableId);
                return;
            }
            settlement.release(reservation);
            count("refunded", "released");
        } else {
            anomaly(payableId);
        }
    }

    private void anomaly(Long reservationId) {
        log.error("Drop refund state or attempt mismatch reservationId={}", reservationId);
        count("refunded", "anomaly");
    }

    private ApprovalDecision reject(String event, String reason) {
        count(event, "rejected");
        return ApprovalDecision.reject(reason);
    }

    private void count(String event, String result) {
        // Count only callbacks whose enclosing payment transaction commits.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { increment(event, result); }
            });
        } else {
            increment(event, result);
        }
    }

    private void increment(String event, String result) {
        try {
            meters.counter("drop.payment.result", "event", event, "result", result).increment();
        } catch (RuntimeException failure) {
            log.error("Drop payment metric failed event={} result={} errorType={}", event, result,
                    failure.getClass().getName());
            meters.counter("drop.payment.metric.failure").increment();
        }
    }
}
