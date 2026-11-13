package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.PaymentStatus;
import com.restless.payment.infrastructure.PaymentRefundRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentRefundService {
    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);
    private final PaymentTransactions transactions;
    private final PaymentRefundRepository refunds;
    private final PgClient pg;
    private final MeterRegistry meters;

    /** TX-R1 commits before PG; all final state is applied by the existing shared TX-C/TX-R2. */
    public RefundPaymentResult refund(RefundPaymentCommand command) {
        try {
            PaymentTransactions.RefundStep step;
            try {
                step = transactions.beginRefund(command);
            } catch (DataIntegrityViolationException
                    | PessimisticLockingFailureException conflict) {
                try {
                    step = transactions.resolveRefundConflict(command);
                } catch (DataIntegrityViolationException
                        | PessimisticLockingFailureException repeated) {
                    throw new BusinessException(ErrorCode.PAYMENT_REFUND_IN_PROGRESS);
                }
            }
            if (!step.created()) return response(step.refundId());
            var cancellation =
                    transactions.cancellation(command.paymentId(), PaymentStatus.CANCEL_PENDING);
            // Reconciliation can finish/reject this request before we resume. Never send a
            // newer refund's cancellation and then apply its result to this older request.
            if (cancellation == null || !cancellation.refundId().equals(step.refundId()))
                return response(step.refundId());
            PgCancelResult result;
            Timer.Sample timer = Timer.start(meters);
            try {
                result = pg.cancel(cancellation.command());
            } catch (RuntimeException error) {
                failure(
                        "PG refund cancellation failed",
                        command.paymentId(),
                        step.refundId(),
                        error);
                result = new PgCancelResult.Unknown("PG refund cancellation failed", null);
            } finally {
                timer.stop(meters.timer("payment.pg.cancel", "kind", cancellation.kind().name()));
            }
            try {
                transactions.applyCancellation(
                        command.paymentId(), step.refundId(), PaymentStatus.CANCEL_PENDING, result);
            } catch (RuntimeException error) {
                meters.counter("payment.refund.apply.failure").increment();
                failure(
                        "Refund result transaction rolled back",
                        command.paymentId(),
                        step.refundId(),
                        error);
            }
            return response(step.refundId());
        } catch (BusinessException error) {
            if (error.getErrorCode() == ErrorCode.PAYMENT_NOT_REFUNDABLE)
                meters.counter("payment.refund", "result", "not_refundable").increment();
            throw error;
        }
    }

    private RefundPaymentResult response(Long refundId) {
        var refund = refunds.findById(refundId).orElseThrow();
        return switch (refund.getStatus()) {
            case COMPLETED -> {
                meters.counter("payment.refund", "result", "completed").increment();
                yield new RefundPaymentResult(refundId, refund.getStatus());
            }
            case FAILED -> {
                meters.counter("payment.refund", "result", "rejected").increment();
                throw new BusinessException(
                        ErrorCode.PAYMENT_REFUND_REJECTED,
                        refund.getFailureCode() == null
                                ? null
                                : Map.of("code", refund.getFailureCode()));
            }
            case REQUESTED, UNKNOWN -> {
                meters.counter("payment.refund", "result", "unknown").increment();
                yield new RefundPaymentResult(refundId, refund.getStatus());
            }
        };
    }

    private void failure(String message, Long paymentId, Long refundId, RuntimeException error) {
        // Exception messages can contain HTTP payloads, paymentKey or credentials.
        log.error(
                "{} paymentId={} refundId={} errorType={} stack={}",
                message,
                paymentId,
                refundId,
                error.getClass().getName(),
                Arrays.toString(error.getStackTrace()));
    }
}
