package com.restless.payment.infrastructure.toss;

import com.restless.payment.application.pg.PgApprovalResult;
import com.restless.payment.application.pg.PgCancelResult;
import com.restless.payment.application.pg.PgQueryResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Set;

/** Provider errors are ambiguous unless an explicit final outcome is known (INV-10). */
public final class TossResponseClassifier {
    private static final Set<String> APPROVAL_REJECTIONS = Set.of(
        "REJECT_CARD_COMPANY", "REJECT_ACCOUNT_PAYMENT", "INVALID_STOPPED_CARD",
        "INVALID_CARD_EXPIRATION", "EXCEED_MAX_AMOUNT", "NOT_FOUND_PAYMENT_SESSION");
    private static final Set<String> CONFIGURATION_ERRORS = Set.of(
        "UNAUTHORIZED_KEY", "INVALID_API_KEY", "INVALID_REQUEST", "FORBIDDEN_REQUEST",
        "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_SECRET_KEY");

    private TossResponseClassifier() { }

    public static PgApprovalResult approvalError(int httpStatus, String code, String message) {
        if (isClientError(httpStatus) && code != null && APPROVAL_REJECTIONS.contains(code)) {
            return new PgApprovalResult.Rejected(code, message);
        }
        return new PgApprovalResult.Unknown("HTTP " + httpStatus, code);
    }

    public static final String ALREADY_CANCELED = "ALREADY_CANCELED_PAYMENT";

    /**
     * ALREADY_CANCELED_PAYMENT carries no cancel transaction key, so it is not a finished Cancelled.
     * The client looks the payment up to recover the key; until then it stays Unknown.
     */
    public static PgCancelResult cancelError(int httpStatus, String code, String message) {
        if (isClientError(httpStatus)) {
            if (ALREADY_CANCELED.equals(code)) {
                return new PgCancelResult.Unknown("Already cancelled; cancel transaction lookup required", code);
            }
            if ("NOT_CANCELABLE_PAYMENT".equals(code)) {
                return new PgCancelResult.Rejected(code, message);
            }
        }
        return new PgCancelResult.Unknown("HTTP " + httpStatus, code);
    }

    public static PgQueryResult queryError(int httpStatus, String code) {
        if (httpStatus == 404 && "NOT_FOUND_PAYMENT".equals(code)) {
            return new PgQueryResult.NotFound();
        }
        return new PgQueryResult.Unknown("HTTP " + httpStatus);
    }

    /** Non-success statuses can be classified without approval/cancellation metadata. */
    public static PgQueryResult queryStatus(String status, String code, String message) {
        if (status == null) return new PgQueryResult.Unknown("Missing payment status");
        return switch (status) {
            case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> new PgQueryResult.InProgress(status);
            case "ABORTED", "EXPIRED" -> new PgQueryResult.NotApproved(status, code, message);
            default -> new PgQueryResult.Unknown("Unresolved payment status");
        };
    }

    public static boolean isConfigurationError(int httpStatus, String code) {
        return httpStatus == 401 || (code != null && CONFIGURATION_ERRORS.contains(code));
    }

    static PgApprovalResult approvalSuccess(TossPaymentResponse p) {
        requireApproval(p);
        return new PgApprovalResult.Approved(p.paymentKey(), p.orderId(), p.totalAmount(), instant(p.approvedAt()));
    }

    static PgQueryResult querySuccess(TossPaymentResponse p) {
        if ("DONE".equals(p.status())) {
            requireApproval(p);
            return new PgQueryResult.Approved(p.paymentKey(), p.orderId(), p.totalAmount(), instant(p.approvedAt()));
        }
        if ("CANCELED".equals(p.status())) {
            var cancel = cancellation(p);
            return new PgQueryResult.Cancelled(cancel.transactionKey(), instant(cancel.canceledAt()));
        }
        return queryStatus(p.status(), p.failure() == null ? null : p.failure().code(),
            p.failure() == null ? null : p.failure().message());
    }

    static PgCancelResult cancelSuccess(TossPaymentResponse p) {
        var cancel = cancellation(p);
        return new PgCancelResult.Cancelled(cancel.transactionKey(), instant(cancel.canceledAt()));
    }

    private static void requireApproval(TossPaymentResponse p) {
        if (!"DONE".equals(p.status()) || blank(p.paymentKey()) || blank(p.orderId())
            || p.totalAmount() == null || p.totalAmount() < 0 || blank(p.approvedAt())) {
            throw new IllegalArgumentException("Incomplete approved payment");
        }
    }

    private static TossCancelResponse cancellation(TossPaymentResponse p) {
        if (!"CANCELED".equals(p.status()) || p.cancels() == null || p.cancels().isEmpty()) {
            throw new IllegalArgumentException("Full cancellation not confirmed");
        }
        // Never infer completion from a pending cancellation or a partial refund.
        for (var cancel : p.cancels()) {
            if (cancel == null || !"DONE".equals(cancel.cancelStatus())
                || blank(cancel.transactionKey()) || blank(cancel.canceledAt())) {
                throw new IllegalArgumentException("Incomplete cancellation metadata");
            }
        }
        return p.cancels().stream().max(Comparator.comparing(c -> instant(c.canceledAt()))).orElseThrow();
    }

    private static Instant instant(String value) { return OffsetDateTime.parse(value).toInstant(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }
}
