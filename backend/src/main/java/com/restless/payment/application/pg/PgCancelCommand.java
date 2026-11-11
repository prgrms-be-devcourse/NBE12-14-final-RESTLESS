package com.restless.payment.application.pg;

public record PgCancelCommand(String paymentKey, String reason, String idempotencyKey) {
}
