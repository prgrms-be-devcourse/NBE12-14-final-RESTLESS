package com.restless.payment.application.pg;

import java.time.Instant;

public sealed interface PgApprovalResult {
    record Approved(String paymentKey, String pgOrderId, long approvedAmount, Instant approvedAt)
        implements PgApprovalResult { }
    record Rejected(String code, String message) implements PgApprovalResult { }
    record Unknown(String reason, String code) implements PgApprovalResult { }
}
