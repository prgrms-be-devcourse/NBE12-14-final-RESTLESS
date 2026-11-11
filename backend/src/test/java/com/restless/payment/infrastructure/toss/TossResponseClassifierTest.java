package com.restless.payment.infrastructure.toss;

import com.restless.payment.application.pg.PgApprovalResult;
import com.restless.payment.application.pg.PgCancelResult;
import com.restless.payment.application.pg.PgQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;

class TossResponseClassifierTest {
    @Test
    void INV10_doneAndCanceledQueryStatesRequireProviderMetadata() {
        var done = new TossPaymentResponse("payment", "order", "DONE", 1234L,
            "2024-02-13T12:18:14+09:00", null, null);
        assertThat(TossResponseClassifier.querySuccess(done)).isEqualTo(new PgQueryResult.Approved(
            "payment", "order", 1234L, Instant.parse("2024-02-13T03:18:14Z")));
        var canceled = new TossPaymentResponse("payment", "order", "CANCELED", 1234L, null,
            List.of(new TossCancelResponse("tx", "2024-02-13T12:18:14+09:00", "DONE")), null);
        assertThat(TossResponseClassifier.querySuccess(canceled)).isEqualTo(new PgQueryResult.Cancelled(
            "tx", Instant.parse("2024-02-13T03:18:14Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECT_CARD_COMPANY", "REJECT_ACCOUNT_PAYMENT", "INVALID_STOPPED_CARD",
        "INVALID_CARD_EXPIRATION", "EXCEED_MAX_AMOUNT", "NOT_FOUND_PAYMENT_SESSION"})
    void INV10_onlyConfirmedApprovalRejectionsAreRejected(String code) {
        assertThat(TossResponseClassifier.approvalError(400, code, "declined"))
            .isEqualTo(new PgApprovalResult.Rejected(code, "declined"));
    }

    @Test
    void INV10_alreadyProcessedPaymentIsUnknownBecausePreviousApprovalMayHaveSucceeded() {
        assertThat(TossResponseClassifier.approvalError(400, "ALREADY_PROCESSED_PAYMENT", "processed"))
            .isInstanceOf(PgApprovalResult.Unknown.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAUTHORIZED_KEY", "INVALID_REQUEST", "FORBIDDEN_REQUEST", "NEW_CODE"})
    void INV10_configurationErrorsAndUnlistedErrorsAreUnknown(String code) {
        assertThat(TossResponseClassifier.approvalError(403, code, "error"))
            .isEqualTo(new PgApprovalResult.Unknown("HTTP 403", code));
    }

    @Test
    void INV10_serverErrorsOverrideEvenAllowlistedCodes() {
        assertThat(TossResponseClassifier.approvalError(500, "REJECT_CARD_COMPANY", "error"))
            .isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(TossResponseClassifier.cancelError(500, "ALREADY_CANCELED_PAYMENT", "error"))
            .isInstanceOf(PgCancelResult.Unknown.class);
        assertThat(TossResponseClassifier.approvalError(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "error"))
            .isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(TossResponseClassifier.approvalError(502, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "error"))
            .isInstanceOf(PgApprovalResult.Unknown.class);
    }

    @Test
    void INV10_cancelOnlyConfirmsKnownOutcomes() {
        // No cancel transaction key in this response, so it is not a finished Cancelled until looked up.
        assertThat(TossResponseClassifier.cancelError(400, "ALREADY_CANCELED_PAYMENT", "cancelled"))
            .isEqualTo(new PgCancelResult.Unknown(
                "Already cancelled; cancel transaction lookup required", "ALREADY_CANCELED_PAYMENT"));
        assertThat(TossResponseClassifier.cancelError(403, "NOT_CANCELABLE_PAYMENT", "declined"))
            .isEqualTo(new PgCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "declined"));
        assertThat(TossResponseClassifier.cancelError(400, "FAILED_REFUND_PROCESS", "uncertain"))
            .isInstanceOf(PgCancelResult.Unknown.class);
        assertThat(TossResponseClassifier.cancelError(400, "NEW_CODE", "uncertain"))
            .isInstanceOf(PgCancelResult.Unknown.class);
    }

    @Test
    void INV10_onlyExactNotFoundResponseIsNotFound() {
        assertThat(TossResponseClassifier.queryError(404, "NOT_FOUND_PAYMENT"))
            .isEqualTo(new PgQueryResult.NotFound());
        assertThat(TossResponseClassifier.queryError(500, "NOT_FOUND_PAYMENT"))
            .isInstanceOf(PgQueryResult.Unknown.class);
        assertThat(TossResponseClassifier.queryError(404, "OTHER"))
            .isInstanceOf(PgQueryResult.Unknown.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT"})
    void INV10_pendingQueryStatusesRemainInProgress(String status) {
        assertThat(TossResponseClassifier.queryStatus(status, null, null))
            .isEqualTo(new PgQueryResult.InProgress(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    void INV10_onlyTerminalUnapprovedStatusesAreNotApproved(String status) {
        assertThat(TossResponseClassifier.queryStatus(status, "code", "message"))
            .isEqualTo(new PgQueryResult.NotApproved(status, "code", "message"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL_CANCELED", "FUTURE_STATUS", ""})
    void INV10_unrecognizedQueryStatusesRemainUnknown(String status) {
        assertThat(TossResponseClassifier.queryStatus(status, null, null))
            .isInstanceOf(PgQueryResult.Unknown.class);
    }
}
