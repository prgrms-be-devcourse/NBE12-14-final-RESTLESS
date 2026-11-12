package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.payment.application.payable.*;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.PaymentRefundRepository;
import com.restless.payment.infrastructure.PaymentRepository;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Each callback commits independently. Business errors are returned, then thrown outside the
 * transaction.
 */
@Component
public class PaymentTransactions {
    private static final Logger log = LoggerFactory.getLogger(PaymentTransactions.class);
    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentResultHandlerRegistry registry;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public PaymentTransactions(
            PaymentRepository payments,
            PaymentRefundRepository refunds,
            PaymentResultHandlerRegistry registry,
            Clock clock,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.refunds = refunds;
        this.registry = registry;
        this.clock = clock;
        this.meters = meters;
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Current committed reads on UNIQUE-conflict retries; avoid empty-range gap locks at
        // preparation.
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PreparePaymentResult prepare(PreparePaymentCommand command) {
        return tx.execute(
                ignored -> {
                    if (!registry.supports(command.payableType()))
                        throw new BusinessException(ErrorCode.PAYMENT_NOT_PAYABLE);
                    PayableSnapshot snapshot =
                            registry.get(command.payableType())
                                    .load(command.payableId())
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.PAYMENT_NOT_PAYABLE));
                    if (!snapshot.ownerUserId().equals(command.userId()))
                        throw new BusinessException(ErrorCode.PAYMENT_TARGET_NOT_FOUND);
                    Instant now = clock.instant();
                    if (snapshot.expiresAt() != null && now.isAfter(snapshot.expiresAt())) {
                        throw new BusinessException(ErrorCode.PAYMENT_WINDOW_EXPIRED);
                    }
                    var replay =
                            payments.findByPayableTypeAndPayableIdAndIdempotencyKey(
                                    command.payableType(),
                                    command.payableId(),
                                    command.idempotencyKey());
                    if (replay.isPresent())
                        return prepared(
                                replay.get(), command.userId(), snapshot.orderName(), false);

                    var active =
                            payments.findActiveForUpdate(
                                    command.payableType(), command.payableId());
                    // Another preparation may have committed this same key while this transaction
                    // waited for its lock.
                    replay =
                            payments.findByPayableTypeAndPayableIdAndIdempotencyKey(
                                    command.payableType(),
                                    command.payableId(),
                                    command.idempotencyKey());
                    if (replay.isPresent())
                        return prepared(
                                replay.get(), command.userId(), snapshot.orderName(), false);
                    if (active.isPresent()) {
                        Payment old = active.get();
                        if (!old.getUserId().equals(command.userId()))
                            throw new BusinessException(ErrorCode.PAYMENT_TARGET_NOT_FOUND);
                        switch (old.getStatus()) {
                            case READY -> {
                                old.fail("SUPERSEDED", "Replaced by a new payment attempt", now);
                                payments
                                        .flush(); // Release generated-column UNIQUE before the
                                                  // IDENTITY insert.
                            }
                            case PROCESSING ->
                                    throw new BusinessException(
                                            ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
                            case UNKNOWN ->
                                    throw new BusinessException(ErrorCode.PAYMENT_UNDER_REVIEW);
                            default -> throw new BusinessException(ErrorCode.PAYMENT_NOT_PAYABLE);
                        }
                    }
                    Payment payment =
                            payments.saveAndFlush(
                                    Payment.ready(
                                            command.payableType(),
                                            command.payableId(),
                                            command.userId(),
                                            snapshot.amount(),
                                            command.idempotencyKey(),
                                            snapshot.expiresAt(),
                                            now));
                    return prepared(payment, command.userId(), snapshot.orderName(), true);
                });
    }

    private PreparePaymentResult prepared(
            Payment payment, Long userId, String orderName, boolean created) {
        if (!payment.getUserId().equals(userId))
            throw new BusinessException(ErrorCode.PAYMENT_TARGET_NOT_FOUND);
        return new PreparePaymentResult(
                payment.getId(),
                payment.getPgOrderId(),
                payment.getAmount(),
                orderName,
                payment.getExpiresAt(),
                payment.getStatus(),
                created);
    }

    Step begin(ConfirmPaymentCommand command) {
        return tx.execute(
                ignored -> {
                    Payment payment =
                            payments.findByPgOrderIdForUpdate(command.orderId())
                                    .filter(p -> p.getUserId().equals(command.userId()))
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.PAYMENT_NOT_FOUND));
                    if (payment.getStatus() != PaymentStatus.READY) return current(payment);
                    if (!payment.matchesAmount(command.amount()))
                        throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
                    Instant now = clock.instant();
                    PaymentResultHandler handler = registry.get(payment.getPayableType());
                    if (payment.getExpiresAt() != null && now.isAfter(payment.getExpiresAt())) {
                        payment.fail("EXPIRED", "Payment approval deadline has passed", now);
                        handler.onFailed(
                                payment.getPayableId(),
                                PaymentAttemptContext.notStarted(payment.getId()));
                        return Step.error(payment, ErrorCode.PAYMENT_WINDOW_EXPIRED, null);
                    }
                    ApprovalDecision decision =
                            handler.onApprovalStarting(
                                    payment.getPayableId(),
                                    new PaymentAttemptContext(payment.getId(), now));
                    if (decision.rejected()) {
                        payment.fail("APPROVAL_REJECTED", bounded(decision.reason(), 500), now);
                        return Step.error(
                                payment,
                                ErrorCode.PAYMENT_NOT_PAYABLE,
                                Map.of("reason", decision.reason()));
                    }
                    payment.startProcessing(command.paymentKey(), now);
                    return new Step(
                            payment.getId(),
                            payment.getStatus(),
                            null,
                            null,
                            new PgApproveCommand(
                                    payment.getPaymentKey(),
                                    payment.getPgOrderId(),
                                    payment.getAmount(),
                                    "approve-" + payment.getId()),
                            null);
                });
    }

    Step applyApproval(Long paymentId, PgApprovalResult result) {
        return tx.execute(
                ignored -> {
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    if (payment.getStatus() != PaymentStatus.PROCESSING) return current(payment);
                    PaymentResultHandler handler = registry.get(payment.getPayableType());
                    PaymentAttemptContext attempt =
                            new PaymentAttemptContext(
                                    payment.getId(), payment.getApprovalStartedAt());
                    Instant now = clock.instant();
                    return switch (result) {
                        case PgApprovalResult.Approved approved -> {
                            if (approved.approvedAmount() != payment.getAmount()) {
                                payment.markUnknown(now);
                                handler.onUnknown(payment.getPayableId(), attempt);
                                log.error(
                                        "PG approval amount mismatch paymentId={} expected={}"
                                            + " actual={}",
                                        paymentId,
                                        payment.getAmount(),
                                        approved.approvedAmount());
                                meters.counter("payment.amount.mismatch").increment();
                                yield current(payment);
                            }
                            payment.approve(approved.approvedAt(), now);
                            ApprovalDecision decision =
                                    handler.onApproved(
                                            payment.getPayableId(),
                                            new ApprovedPayment(
                                                    paymentId,
                                                    payment.getPgOrderId(),
                                                    payment.getPaymentKey(),
                                                    payment.getAmount(),
                                                    payment.getApprovalStartedAt(),
                                                    approved.approvedAt()));
                            if (decision.accepted()) yield current(payment);
                            payment.requestCancel(now);
                            PaymentRefund refund =
                                    refunds.saveAndFlush(
                                            PaymentRefund.request(
                                                    paymentId,
                                                    PaymentRefundKind.COMPENSATION,
                                                    payment.getAmount(),
                                                    "compensation-" + paymentId,
                                                    bounded(decision.reason(), 200),
                                                    now));
                            yield new Step(
                                    paymentId,
                                    payment.getStatus(),
                                    null,
                                    null,
                                    null,
                                    new Compensation(
                                            paymentId,
                                            refund.getId(),
                                            payment.getPaymentKey(),
                                            decision.reason(),
                                            refund.getIdempotencyKey()));
                        }
                        case PgApprovalResult.Rejected rejected -> {
                            payment.fail(
                                    bounded(rejected.code(), 100),
                                    bounded(rejected.message(), 500),
                                    now);
                            handler.onFailed(payment.getPayableId(), attempt);
                            yield Step.error(
                                    payment, ErrorCode.PAYMENT_FAILED, pgCode(rejected.code()));
                        }
                        case PgApprovalResult.Unknown unknown -> {
                            payment.markUnknown(now);
                            handler.onUnknown(payment.getPayableId(), attempt);
                            yield current(payment);
                        }
                    };
                });
    }

    String applyCancellation(Compensation compensation, PgCancelResult result) {
        return tx.execute(
                ignored -> {
                    // Always payment first, refund second; only the expected pair can invoke a
                    // Handler.
                    Payment payment =
                            payments.findByIdForUpdate(compensation.paymentId()).orElseThrow();
                    PaymentRefund refund =
                            refunds.findByIdForUpdate(compensation.refundId()).orElseThrow();
                    if (payment.getStatus() != PaymentStatus.CANCEL_PENDING
                            || refund.getStatus() != PaymentRefundStatus.REQUESTED) {
                        return switch (refund.getStatus()) {
                            case COMPLETED -> "COMPLETED";
                            case FAILED -> "REJECTED";
                            default -> "UNKNOWN";
                        };
                    }
                    Instant now = clock.instant();
                    return switch (result) {
                        case PgCancelResult.Cancelled cancelled -> {
                            refund.complete(cancelled.transactionKey(), now);
                            payment.completeCancel(now);
                            registry.get(payment.getPayableType())
                                    .onRefunded(
                                            payment.getPayableId(),
                                            new RefundContext(
                                                    payment.getId(),
                                                    PaymentRefundKind.COMPENSATION));
                            yield "COMPLETED";
                        }
                        case PgCancelResult.Rejected rejected -> {
                            refund.fail(
                                    bounded(rejected.code(), 100),
                                    bounded(rejected.message(), 500),
                                    now);
                            payment.revertCancel(now);
                            log.error(
                                    "Payment compensation rejected paymentId={} refundId={}",
                                    payment.getId(),
                                    refund.getId());
                            meters.counter("payment.compensation.rejected").increment();
                            yield "REJECTED";
                        }
                        case PgCancelResult.Unknown unknown -> {
                            refund.markUnknown(now);
                            payment.markCancelUnknown(now);
                            yield "UNKNOWN";
                        }
                    };
                });
    }

    private static Map<String, Object> pgCode(String code) {
        return code == null ? null : Map.of("code", code);
    }

    private static String bounded(String value, int length) {
        return value == null ? null : value.substring(0, Math.min(value.length(), length));
    }

    private Step current(Payment payment) {
        return switch (payment.getStatus()) {
            case FAILED ->
                    Step.error(payment, ErrorCode.PAYMENT_FAILED, pgCode(payment.getFailureCode()));
            case CANCEL_PENDING, CANCEL_UNKNOWN, CANCELLED ->
                    Step.error(payment, ErrorCode.PAYMENT_NOT_PAYABLE, null);
            default -> new Step(payment.getId(), payment.getStatus(), null, null, null, null);
        };
    }

    record Step(
            Long paymentId,
            PaymentStatus status,
            ErrorCode error,
            Map<String, Object> details,
            PgApproveCommand approval,
            Compensation compensation) {
        static Step error(Payment p, ErrorCode error, Map<String, Object> details) {
            return new Step(p.getId(), p.getStatus(), error, details, null, null);
        }
    }

    record Compensation(
            Long paymentId,
            Long refundId,
            String paymentKey,
            String reason,
            String idempotencyKey) {}
}
