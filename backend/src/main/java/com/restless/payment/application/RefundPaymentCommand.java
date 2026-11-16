package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;

public record RefundPaymentCommand(
        Long userId, Long paymentId, String idempotencyKey, String reason) {
    public RefundPaymentCommand {
        if (userId == null
                || paymentId == null
                || paymentId <= 0
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 64
                || reason == null
                || reason.isBlank()
                || reason.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
