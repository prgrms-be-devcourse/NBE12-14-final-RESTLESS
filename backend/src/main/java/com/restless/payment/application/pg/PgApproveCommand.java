package com.restless.payment.application.pg;

public record PgApproveCommand(String paymentKey, String pgOrderId, long amount, String idempotencyKey) {
}
