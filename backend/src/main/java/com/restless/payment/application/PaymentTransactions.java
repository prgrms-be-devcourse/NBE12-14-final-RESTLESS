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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
                    // Match approval/result callbacks: payment first, then the Handler's domain
                    // row. load() may acquire a domain row lock too.
                    var active =
                            payments.findActiveForUpdate(
                                    command.payableType(), command.payableId());
                    PayableSnapshot snapshot =
                            registry.get(command.payableType())
                                    .load(command.payableId())
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.PAYMENT_NOT_PAYABLE));
                    if (!snapshot.ownerUserId().equals(command.userId()))
                        throw new BusinessException(ErrorCode.PAYMENT_TARGET_NOT_FOUND);
                    // A free order has nothing to charge or refund through the PG. The domain
                    // confirms it without a payment (payment_refunds.amount must be positive).
                    if (snapshot.amount() == 0)
                        throw new BusinessException(
                                ErrorCode.PAYMENT_NOT_PAYABLE, Map.of("reason", "FREE_ORDER"));
                    Instant now = clock.instant();
                    if (snapshot.expiresAt() != null && now.isAfter(snapshot.expiresAt())) {
                        throw new BusinessException(ErrorCode.PAYMENT_WINDOW_EXPIRED);
                    }
                    // Another preparation may have committed this same key while this transaction
                    // waited for either lock. Validate the snapshot before replaying as usual.
                    var replay =
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
                                payments.flush(); // Release generated-column UNIQUE before the
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
                    if (!approvalPending(payment)) return current(payment);
                    return applyApprovalLocked(payment, result);
                });
    }

    private Step applyApprovalLocked(Payment payment, PgApprovalResult result) {
        Long paymentId = payment.getId();
        PaymentResultHandler handler = registry.get(payment.getPayableType());
        PaymentAttemptContext attempt =
                new PaymentAttemptContext(payment.getId(), payment.getApprovalStartedAt());
        Instant now = clock.instant();
        return switch (result) {
            case PgApprovalResult.Approved approved -> {
                if (approved.approvedAmount() != payment.getAmount()) {
                    markUnknown(payment, handler, attempt, now);
                    log.error(
                            "PG approval amount mismatch paymentId={} expected={}" + " actual={}",
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
                payment.fail(bounded(rejected.code(), 100), bounded(rejected.message(), 500), now);
                handler.onFailed(payment.getPayableId(), attempt);
                yield Step.error(payment, ErrorCode.PAYMENT_FAILED, pgCode(rejected.code()));
            }
            case PgApprovalResult.Unknown unknown -> {
                markUnknown(payment, handler, attempt, now);
                yield current(payment);
            }
        };
    }

    private boolean approvalPending(Payment payment) {
        return payment.getStatus() == PaymentStatus.PROCESSING
                || payment.getStatus() == PaymentStatus.UNKNOWN;
    }

    private void markUnknown(
            Payment payment,
            PaymentResultHandler handler,
            PaymentAttemptContext attempt,
            Instant now) {
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            payment.markUnknown(now);
            handler.onUnknown(payment.getPayableId(), attempt);
        }
    }

    /** TX-R1: payment first; callback and refund request commit atomically before any PG call. */
    RefundStep beginRefund(RefundPaymentCommand command) {
        return tx.execute(
                ignored -> {
                    Payment payment = ownedRefundPayment(command);
                    var existing =
                            refunds.findByPaymentIdAndKindAndClientKey(
                                    payment.getId(),
                                    PaymentRefundKind.CUSTOMER_REFUND,
                                    command.idempotencyKey());
                    if (existing.isPresent()) return refundReplay(payment, existing.get());
                    requireRefundable(payment);
                    var context =
                            new RefundContext(payment.getId(), PaymentRefundKind.CUSTOMER_REFUND);
                    ApprovalDecision decision =
                            registry.get(payment.getPayableType())
                                    .onRefundRequesting(payment.getPayableId(), context);
                    if (decision.rejected())
                        throw new BusinessException(
                                ErrorCode.PAYMENT_NOT_REFUNDABLE,
                                Map.of("reason", decision.reason()));
                    Instant now = clock.instant();
                    payment.requestCancel(now);
                    var refund =
                            refunds.saveAndFlush(
                                    PaymentRefund.requestCustomer(
                                            payment.getId(),
                                            payment.getAmount(),
                                            command.idempotencyKey(),
                                            command.reason(),
                                            now));
                    refundMetricAfterCommit(refund, "requested");
                    return new RefundStep(refund.getId(), true);
                });
    }

    /**
     * A failed UNIQUE/locking transaction has rolled back. Re-read once; never issue a second PG.
     */
    RefundStep resolveRefundConflict(RefundPaymentCommand command) {
        return tx.execute(
                ignored -> {
                    Payment payment = ownedRefundPayment(command);
                    var existing =
                            refunds.findByPaymentIdAndKindAndClientKey(
                                    payment.getId(),
                                    PaymentRefundKind.CUSTOMER_REFUND,
                                    command.idempotencyKey());
                    if (existing.isPresent()) return refundReplay(payment, existing.get());
                    requireRefundable(payment);
                    // A transient lock conflict may leave no durable request. The client can retry
                    // its key.
                    throw new BusinessException(ErrorCode.PAYMENT_REFUND_IN_PROGRESS);
                });
    }

    private Payment ownedRefundPayment(RefundPaymentCommand command) {
        return payments.findByIdForUpdate(command.paymentId())
                .filter(p -> p.getUserId().equals(command.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private RefundStep refundReplay(Payment payment, PaymentRefund refund) {
        return new RefundStep(refund.getId(), false);
    }

    private void requireRefundable(Payment payment) {
        switch (payment.getStatus()) {
            case APPROVED -> {
                if (payment.getAmount() <= 0)
                    throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
            }
            case CANCEL_PENDING, CANCEL_UNKNOWN ->
                    throw new BusinessException(ErrorCode.PAYMENT_REFUND_IN_PROGRESS);
            case CANCELLED -> throw new BusinessException(ErrorCode.PAYMENT_ALREADY_REFUNDED);
            default -> throw new BusinessException(ErrorCode.PAYMENT_NOT_REFUNDABLE);
        }
    }

    record RefundStep(Long refundId, boolean created) {}

    String applyCancellation(Compensation compensation, PgCancelResult result) {
        return applyCancellation(compensation.paymentId(), compensation.refundId(), result);
    }

    /** Shared TX-C for compensation, reconciliation and customer refunds. Kind is always stored. */
    public String applyCancellation(Long paymentId, Long refundId, PgCancelResult result) {
        return applyCancellation(paymentId, refundId, null, result);
    }

    String applyCancellation(
            Long paymentId, Long refundId, PaymentStatus expected, PgCancelResult result) {
        return tx.execute(
                ignored -> {
                    // Always payment first, refund second; only the expected pair can invoke a
                    // Handler.
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    PaymentRefund refund = refunds.findByIdForUpdate(refundId).orElseThrow();
                    if (!refund.getPaymentId().equals(paymentId))
                        throw new IllegalArgumentException("Refund does not belong to payment");
                    if ((expected != null && payment.getStatus() != expected)
                            || !cancelPending(payment)
                            || (refund.getStatus() != PaymentRefundStatus.REQUESTED
                                    && refund.getStatus() != PaymentRefundStatus.UNKNOWN)) {
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
                                            new RefundContext(payment.getId(), refund.getKind()));
                            refundMetricAfterCommit(refund, "completed");
                            yield "COMPLETED";
                        }
                        case PgCancelResult.Rejected rejected -> {
                            refund.fail(
                                    bounded(rejected.code(), 100),
                                    bounded(rejected.message(), 500),
                                    now);
                            payment.revertCancel(now);
                            if (refund.getKind() == PaymentRefundKind.CUSTOMER_REFUND) {
                                registry.get(payment.getPayableType())
                                        .onRefundRejected(
                                                payment.getPayableId(),
                                                new RefundContext(
                                                        payment.getId(), refund.getKind()));
                            }
                            log.error(
                                    "Payment cancellation rejected paymentId={} refundId={}"
                                            + " kind={}",
                                    payment.getId(),
                                    refund.getId(),
                                    refund.getKind());
                            meters.counter(
                                            "payment.cancel.rejected",
                                            "kind",
                                            refund.getKind().name())
                                    .increment();
                            if (refund.getKind() == PaymentRefundKind.COMPENSATION)
                                meters.counter("payment.compensation.rejected").increment();
                            refundMetricAfterCommit(refund, "rejected");
                            yield "REJECTED";
                        }
                        case PgCancelResult.Unknown unknown -> {
                            if (refund.getStatus() == PaymentRefundStatus.REQUESTED) {
                                refund.markUnknown(now);
                                refundMetricAfterCommit(refund, "unknown");
                            }
                            if (payment.getStatus() == PaymentStatus.CANCEL_PENDING)
                                payment.markCancelUnknown(now);
                            payment.recordReconciliationCheck(now);
                            yield "UNKNOWN";
                        }
                    };
                });
    }

    /**
     * Count committed customer state transitions, including reconciler recovery, never HTTP replay.
     */
    private void refundMetricAfterCommit(PaymentRefund refund, String result) {
        if (refund.getKind() != PaymentRefundKind.CUSTOMER_REFUND) return;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            meters.counter("payment.refund", "result", result).increment();
                        } catch (RuntimeException error) {
                            log.error(
                                    "Refund metric failed refundId={} result={} errorType={}",
                                    refund.getId(),
                                    result,
                                    error.getClass().getName());
                            meters.counter("payment.refund.metric.failure").increment();
                        }
                    }
                });
    }

    private boolean cancelPending(Payment payment) {
        return payment.getStatus() == PaymentStatus.CANCEL_PENDING
                || payment.getStatus() == PaymentStatus.CANCEL_UNKNOWN;
    }

    Step reconcileApproval(
            Long paymentId,
            PaymentStatus expected,
            PgQueryResult result,
            java.time.Duration notFoundFinalAfter) {
        return tx.execute(
                ignored -> {
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    if (payment.getStatus() != expected || !approvalPending(payment))
                        return current(payment);
                    Instant now = clock.instant();
                    payment.recordReconciliationCheck(now);
                    return switch (result) {
                        // Single conversion point: both confirm and reconciliation use the same
                        // TX-B.
                        case PgQueryResult.Approved approved ->
                                applyApprovalLocked(
                                        payment,
                                        new PgApprovalResult.Approved(
                                                approved.paymentKey(),
                                                approved.pgOrderId(),
                                                approved.approvedAmount(),
                                                approved.approvedAt()));
                        case PgQueryResult.NotApproved rejected ->
                                applyApprovalLocked(
                                        payment,
                                        new PgApprovalResult.Rejected(
                                                rejected.code(), rejected.message()));
                        case PgQueryResult.NotFound ignoredResult -> {
                            if (payment.getApprovalStartedAt() != null
                                    && !now.isBefore(
                                            payment.getApprovalStartedAt()
                                                    .plus(notFoundFinalAfter))) {
                                yield applyApprovalLocked(
                                        payment,
                                        new PgApprovalResult.Rejected(
                                                "PG_NOT_FOUND",
                                                "PG payment absent beyond final approval window"));
                            }
                            yield applyApprovalLocked(
                                    payment,
                                    new PgApprovalResult.Unknown("PG payment not found yet", null));
                        }
                        case PgQueryResult.Unknown unknown ->
                                applyApprovalLocked(
                                        payment,
                                        new PgApprovalResult.Unknown("PG query unresolved", null));
                        case PgQueryResult.Cancelled cancelled -> {
                            anomaly(paymentId, "unexpected_cancelled");
                            yield applyApprovalLocked(
                                    payment,
                                    new PgApprovalResult.Unknown(
                                            "Unexpected external cancellation", null));
                        }
                        case PgQueryResult.InProgress progress -> current(payment);
                    };
                });
    }

    boolean expireReady(Long paymentId) {
        return tx.execute(
                ignored -> {
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    Instant now = clock.instant();
                    if (payment.getStatus() != PaymentStatus.READY
                            || payment.getExpiresAt() == null
                            || !payment.getExpiresAt().isBefore(now)) return false;
                    payment.fail("EXPIRED", "Payment approval deadline has passed", now);
                    registry.get(payment.getPayableType())
                            .onFailed(
                                    payment.getPayableId(),
                                    PaymentAttemptContext.notStarted(paymentId));
                    return true;
                });
    }

    /** Short locked read, committed before querying the PG; payment -> refund lock order. */
    Cancellation cancellation(Long paymentId, PaymentStatus expected) {
        return tx.execute(
                ignored -> {
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    if (payment.getStatus() != expected || !cancelPending(payment)) return null;
                    var refund = refunds.findPendingForUpdate(paymentId);
                    if (refund.isEmpty()) {
                        anomaly(paymentId, "missing_refund");
                        return null;
                    }
                    var r = refund.get();
                    return new Cancellation(
                            paymentId,
                            r.getId(),
                            payment.getPaymentKey(),
                            r.getReason(),
                            r.getIdempotencyKey(),
                            r.getKind());
                });
    }

    void recordCancelCheck(Long paymentId, PaymentStatus expected) {
        tx.executeWithoutResult(
                ignored -> {
                    Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
                    if (payment.getStatus() == expected && cancelPending(payment))
                        payment.recordReconciliationCheck(clock.instant());
                });
    }

    private void anomaly(Long paymentId, String reason) {
        log.error("Payment reconciliation anomaly paymentId={} reason={}", paymentId, reason);
        meters.counter("payment.reconcile.anomaly", "reason", reason).increment();
    }

    record Cancellation(
            Long paymentId,
            Long refundId,
            String paymentKey,
            String reason,
            String idempotencyKey,
            PaymentRefundKind kind) {
        PgCancelCommand command() {
            return new PgCancelCommand(paymentKey, reason, idempotencyKey);
        }
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
