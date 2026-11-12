package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.payment.application.pg.*;
import com.restless.payment.domain.PaymentStatus;
import com.restless.payment.infrastructure.PaymentRepository;

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
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentTransactions transactions;
    private final PaymentRepository payments;
    private final PgClient pgClient;
    private final MeterRegistry meters;

    /** Public application entry point, also usable by the fan meeting PaymentPort adapter. */
    public PreparePaymentResult prepare(PreparePaymentCommand command) {
        try {
            PreparePaymentResult result;
            try {
                result = transactions.prepare(command);
            } catch (DataIntegrityViolationException
                    | PessimisticLockingFailureException conflict) {
                // The failed transaction has rolled back. Re-read once in a fresh transaction.
                try {
                    result = transactions.prepare(command);
                } catch (DataIntegrityViolationException
                        | PessimisticLockingFailureException repeatedConflict) {
                    throw new BusinessException(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
                }
            }
            meters.counter("payment.prepare", "result", result.created() ? "created" : "replayed")
                    .increment();
            return result;
        } catch (BusinessException rejected) {
            meters.counter("payment.prepare", "result", "rejected").increment();
            throw rejected;
        }
    }

    /** TX-A commit -> PG (no transaction) -> TX-B commit -> optional compensation PG -> TX-C. */
    public ConfirmPaymentResult confirm(ConfirmPaymentCommand command) {
        PaymentTransactions.Step step = transactions.begin(command);
        if (step.approval() == null) return response(step);
        PgApprovalResult pgResult;
        Timer.Sample timer = Timer.start(meters);
        try {
            pgResult = pgClient.approve(step.approval());
        } catch (RuntimeException error) {
            logFailure("PG approval call failed", step.paymentId(), error);
            pgResult = new PgApprovalResult.Unknown("PG approval call failed", null);
        } finally {
            timer.stop(meters.timer("payment.pg.approve"));
        }
        try {
            step = transactions.applyApproval(step.paymentId(), pgResult);
        } catch (RuntimeException error) {
            resultFailure(step.paymentId(), error);
            return new ConfirmPaymentResult(step.paymentId(), PaymentStatus.PROCESSING);
        }
        if (step.compensation() == null) return response(step);
        PaymentTransactions.Compensation compensation = step.compensation();
        PgCancelResult cancelResult;
        try {
            cancelResult =
                    pgClient.cancel(
                            new PgCancelCommand(
                                    compensation.paymentKey(),
                                    compensation
                                            .reason()
                                            .substring(
                                                    0,
                                                    Math.min(compensation.reason().length(), 200)),
                                    compensation.idempotencyKey()));
        } catch (RuntimeException error) {
            logFailure("PG compensation call failed", compensation.paymentId(), error);
            cancelResult = new PgCancelResult.Unknown("PG compensation call failed", null);
        }
        String refund;
        try {
            refund = transactions.applyCancellation(compensation, cancelResult);
        } catch (RuntimeException error) {
            // Pending state remains durable for reconciliation, including a failed onRefunded
            // callback.
            resultFailure(compensation.paymentId(), error);
            refund = "UNKNOWN";
        }
        meters.counter("payment.confirm", "result", "rejected").increment();
        throw new BusinessException(
                ErrorCode.PAYMENT_REJECTED,
                Map.of("reason", compensation.reason(), "refund", refund));
    }

    public PaymentDetail get(Long paymentId, Long userId) {
        return payments.findById(paymentId)
                .filter(p -> p.getUserId().equals(userId))
                .map(PaymentDetail::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private ConfirmPaymentResult response(PaymentTransactions.Step step) {
        String result =
                step.error() == ErrorCode.PAYMENT_WINDOW_EXPIRED
                        ? "expired"
                        : switch (step.status()) {
                            case APPROVED -> "approved";
                            case FAILED -> "failed";
                            case UNKNOWN -> "unknown";
                            default -> null;
                        };
        if (result != null) meters.counter("payment.confirm", "result", result).increment();
        if (step.error() != null) throw new BusinessException(step.error(), step.details());
        return new ConfirmPaymentResult(step.paymentId(), step.status());
    }

    private void resultFailure(Long paymentId, RuntimeException error) {
        meters.counter("payment.result.apply.failure").increment();
        logFailure("Payment result transaction rolled back", paymentId, error);
    }

    private static void logFailure(String message, Long paymentId, RuntimeException error) {
        // Exception messages can contain HTTP payloads/paymentKey. Record the type and stack
        // without payloads.
        log.error(
                "{} paymentId={} errorType={} stack={}",
                message,
                paymentId,
                error.getClass().getName(),
                Arrays.toString(error.getStackTrace()));
    }
}
