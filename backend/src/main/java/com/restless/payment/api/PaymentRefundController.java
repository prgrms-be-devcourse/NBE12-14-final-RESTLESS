package com.restless.payment.api;

import com.restless.auth.LoginMember;
import com.restless.payment.application.PaymentRefundService;
import com.restless.payment.application.RefundPaymentCommand;
import com.restless.payment.application.RefundPaymentResult;
import com.restless.payment.domain.PaymentRefundStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/{paymentId}/refunds")
@RequiredArgsConstructor
public class PaymentRefundController {
    private final PaymentRefundService service;

    @PostMapping
    public ResponseEntity<RefundPaymentResult> refund(
            @LoginMember Long userId,
            @PathVariable Long paymentId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RefundRequest request) {
        var result =
                service.refund(new RefundPaymentCommand(userId, paymentId, key, request.reason()));
        return ResponseEntity.status(
                        result.status() == PaymentRefundStatus.COMPLETED
                                ? HttpStatus.OK
                                : HttpStatus.ACCEPTED)
                .body(result);
    }

    public record RefundRequest(@NotBlank @Size(max = 200) String reason) {}
}
