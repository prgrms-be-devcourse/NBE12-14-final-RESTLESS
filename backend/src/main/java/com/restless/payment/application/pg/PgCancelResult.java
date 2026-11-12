package com.restless.payment.application.pg;

import java.time.Instant;

public sealed interface PgCancelResult {
    /** Always carries the PG cancel transaction key and time. */
    record Cancelled(String transactionKey, Instant cancelledAt) implements PgCancelResult { }
    record Rejected(String code, String message) implements PgCancelResult { }
    record Unknown(String reason, String code) implements PgCancelResult { }
}
