package com.restless.payment.application.pg;

import java.time.Instant;

public sealed interface PgCancelResult {
    /** Metadata is null when the PG only reports ALREADY_CANCELED_PAYMENT. */
    record Cancelled(String transactionKey, Instant cancelledAt) implements PgCancelResult { }
    record Rejected(String code, String message) implements PgCancelResult { }
    record Unknown(String reason, String code) implements PgCancelResult { }
}
