package com.restless.payment.application.pg;

import java.time.Instant;

public sealed interface PgQueryResult {
    record Approved(String paymentKey, String pgOrderId, long approvedAmount, Instant approvedAt)
        implements PgQueryResult { }
    /** Always carries the PG cancel transaction key and time. */
    record Cancelled(String transactionKey, Instant cancelledAt) implements PgQueryResult { }
    record NotApproved(String status, String code, String message) implements PgQueryResult { }
    record InProgress(String status) implements PgQueryResult { }
    record NotFound() implements PgQueryResult { }
    record Unknown(String reason) implements PgQueryResult { }
}
