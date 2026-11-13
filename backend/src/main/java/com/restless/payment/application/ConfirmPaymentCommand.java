package com.restless.payment.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;

public record ConfirmPaymentCommand(Long userId, String paymentKey, String orderId, long amount) {
    public ConfirmPaymentCommand {
        if (userId == null
                || paymentKey == null
                || paymentKey.isBlank()
                || paymentKey.length() > 200
                || orderId == null
                || orderId.isBlank()
                || orderId.length() > 64
                || amount < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
