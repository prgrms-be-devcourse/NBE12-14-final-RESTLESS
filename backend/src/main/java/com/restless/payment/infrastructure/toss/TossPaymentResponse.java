package com.restless.payment.infrastructure.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(String paymentKey, String orderId, String status, Long totalAmount,
                           String approvedAt, List<TossCancelResponse> cancels, TossErrorResponse failure) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record TossCancelResponse(String transactionKey, String canceledAt, String cancelStatus) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
