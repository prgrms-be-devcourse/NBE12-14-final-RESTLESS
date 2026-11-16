package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.payment.domain.PayableType;

public record PreparePaymentCommand(
        Long userId, PayableType payableType, Long payableId, String idempotencyKey) {
    public PreparePaymentCommand {
        if (userId == null
                || payableType == null
                || payableId == null
                || payableId <= 0
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
