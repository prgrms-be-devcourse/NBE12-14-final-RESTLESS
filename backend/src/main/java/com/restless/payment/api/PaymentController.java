package com.restless.payment.api;

import com.restless.auth.LoginMember;
import com.restless.payment.application.*;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.PaymentStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService service;

    @PostMapping
    public ResponseEntity<PreparedResponse> prepare(
            @LoginMember Long userId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PrepareRequest request) {
        PreparePaymentResult result =
                service.prepare(
                        new PreparePaymentCommand(
                                userId, request.payableType(), request.payableId(), key));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(
                        new PreparedResponse(
                                result.paymentId(),
                                result.pgOrderId(),
                                result.amount(),
                                result.orderName(),
                                result.expiresAt(),
                                result.status(),
                                result.created()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ConfirmedResponse> confirm(
            @LoginMember Long userId, @Valid @RequestBody ConfirmRequest request) {
        ConfirmPaymentResult result =
                service.confirm(
                        new ConfirmPaymentCommand(
                                userId, request.paymentKey(), request.orderId(), request.amount()));
        boolean pending =
                result.status() == PaymentStatus.PROCESSING
                        || result.status() == PaymentStatus.UNKNOWN;
        return ResponseEntity.status(pending ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(new ConfirmedResponse(result.paymentId(), result.status()));
    }

    @GetMapping("/{paymentId}")
    public DetailResponse get(@LoginMember Long userId, @PathVariable Long paymentId) {
        PaymentDetail result = service.get(paymentId, userId);
        return new DetailResponse(
                result.paymentId(),
                result.payableType(),
                result.payableId(),
                result.pgOrderId(),
                result.status(),
                result.amount(),
                result.approvalStartedAt(),
                result.approvedAt(),
                result.failureCode());
    }

    public record PrepareRequest(
            @NotNull PayableType payableType, @NotNull @Positive Long payableId) {}

    public record ConfirmRequest(
            @NotBlank @Size(max = 200) String paymentKey,
            @NotBlank @Size(max = 64) String orderId,
            @NotNull @PositiveOrZero Long amount) {}

    public record PreparedResponse(
            Long paymentId,
            String pgOrderId,
            long amount,
            String orderName,
            Instant expiresAt,
            PaymentStatus status,
            boolean created) {}

    public record ConfirmedResponse(Long paymentId, PaymentStatus status) {}

    public record DetailResponse(
            Long paymentId,
            PayableType payableType,
            Long payableId,
            String pgOrderId,
            PaymentStatus status,
            long amount,
            Instant approvalStartedAt,
            Instant approvedAt,
            String failureCode) {}
}
