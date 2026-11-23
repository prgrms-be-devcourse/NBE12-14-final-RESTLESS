package com.restless.credit.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.credit.domain.CreditCharge;
import com.restless.credit.infrastructure.CreditChargeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 충전 주문 생성 (#316). 결제는 공통 결제 모듈이 하고, 지급은 {@link CreditChargePaymentResultHandler}가 한다. */
@Service
@RequiredArgsConstructor
public class CreditChargeService {
    static final String REQUEST_KEY_UNIQUE = "uk_credit_charges_user_request_key";
    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);

    private final CreditChargeRepository charges;
    private final Clock clock;

    /**
     * 같은 회원·같은 요청 키는 충전 주문 하나다. 금액이 같으면 기존 주문을 돌려주고, 다르면 409.
     *
     * <p>일부러 트랜잭션으로 감싸지 않는다. 같은 키가 동시에 오면 둘 다 조회에서 비어 저장을 시도하고, 늦은 쪽의
     * 저장이 UNIQUE에 막힌다. 그 저장의 트랜잭션은 롤백 전용이 되므로, 기존 행은 저장이 끝난 뒤 새 조회로 읽는다.
     */
    public ChargeResult create(Long userId, long amount, String requestKey) {
        Optional<CreditCharge> previous = charges.findByUserIdAndRequestKey(userId, requestKey);
        if (previous.isPresent()) return replay(previous.get(), amount);
        Instant now = clock.instant();
        try {
            CreditCharge charge = charges.saveAndFlush(
                    CreditCharge.ready(userId, amount, requestKey, now, now.plus(PAYMENT_WINDOW)));
            return new ChargeResult(charge, false);
        } catch (DataIntegrityViolationException e) {
            if (!String.valueOf(e.getMostSpecificCause().getMessage()).contains(REQUEST_KEY_UNIQUE)) throw e;
            CreditCharge charge = charges.findByUserIdAndRequestKey(userId, requestKey).orElseThrow(() -> e);
            return replay(charge, amount);
        }
    }

    private static ChargeResult replay(CreditCharge charge, long amount) {
        if (charge.getAmount() != amount) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    Map.of("header", "Idempotency-Key"));
        }
        return new ChargeResult(charge, true);
    }

    public record ChargeResult(CreditCharge charge, boolean replay) {}
}
