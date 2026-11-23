package com.restless.credit.api;

import com.restless.auth.LoginMember;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.credit.application.CreditChargeService;
import com.restless.credit.application.CreditChargeService.ChargeResult;
import com.restless.credit.domain.CreditCharge;
import com.restless.payment.domain.PayableType;
import java.util.Map;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/credit-charges")
@RequiredArgsConstructor
public class CreditChargeController {
    private final CreditChargeService service;

    @PostMapping
    public ResponseEntity<ChargeResponse> create(@LoginMember Long userId,
            @RequestHeader("Idempotency-Key") String requestKey, @RequestBody ChargeRequest body) {
        if (requestKey.isBlank() || requestKey.length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key 형식이 올바르지 않습니다.",
                    Map.of("header", "Idempotency-Key"));
        }
        long amount = amount(body);
        ChargeResult result = service.create(userId, amount, requestKey);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ChargeResponse.of(result.charge()));
    }

    private static long amount(ChargeRequest body) {
        BigDecimal value = body == null ? null : body.amount();
        if (value == null) invalidAmount();
        long amount;
        try {
            amount = value.longValueExact();
        } catch (ArithmeticException e) {
            invalidAmount();
            return 0;
        }
        if (amount < 1_000 || amount > 1_000_000 || amount % 1_000 != 0) invalidAmount();
        return amount;
    }

    private static void invalidAmount() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "amount 형식이 올바르지 않습니다.",
                Map.of("field", "amount"));
    }

    public record ChargeResponse(Long chargeId, long amount, String status, java.time.Instant expiresAt,
                                 Payable payable) {
        static ChargeResponse of(CreditCharge charge) {
            return new ChargeResponse(charge.getId(), charge.getAmount(), charge.getStatus().name(),
                    charge.getExpiresAt(), new Payable(PayableType.CREDIT_CHARGE, charge.getId()));
        }
    }

    public record ChargeRequest(BigDecimal amount) {}

    public record Payable(PayableType payableType, Long payableId) {}
}
