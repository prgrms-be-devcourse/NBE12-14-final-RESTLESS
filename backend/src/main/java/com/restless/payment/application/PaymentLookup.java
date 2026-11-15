package com.restless.payment.application;

import com.restless.payment.domain.PayableType;
import com.restless.payment.infrastructure.PaymentRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 대상의 최근 결제 시도를 읽기만 한다. 주문 상세가 쓴다 (#109).
 * 주문 모듈이 결제 모듈에서 쓸 수 있는 유일한 타입이다 (OwnedModuleBoundaryTest, #113). 결제 모듈은 주문을 모른다.
 */
@Service
@RequiredArgsConstructor
public class PaymentLookup {
    private final PaymentRepository payments;

    /** 그 회원이 그 결제 대상에 만든 가장 최근 시도. 없거나 모르는 결제 대상 종류면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<Latest> latest(String payableType, Long payableId, Long userId) {
        PayableType type;
        try {
            type = PayableType.valueOf(payableType);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        return payments.findFirstByPayableTypeAndPayableIdAndUserIdOrderByIdDesc(type, payableId, userId)
                .map(p -> new Latest(p.getId(), p.getStatus().name()));
    }

    /** @param status 결제 조회 API와 같은 상태 이름 */
    public record Latest(Long paymentId, String status) {
    }
}
