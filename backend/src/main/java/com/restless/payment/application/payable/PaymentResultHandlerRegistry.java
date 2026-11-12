package com.restless.payment.application.payable;

import com.restless.payment.domain.PayableType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 등록된 Handler 빈을 {@link PayableType}으로 찾는다. 결제 모듈은 도메인 패키지를 import하지 않고 이 Registry만 쓴다.
 * 같은 타입에 구현체가 둘 이상이면 기동을 실패시킨다.
 */
@Component
public class PaymentResultHandlerRegistry {
    private final Map<PayableType, PaymentResultHandler> handlers = new EnumMap<>(PayableType.class);

    public PaymentResultHandlerRegistry(ObjectProvider<PaymentResultHandler> handlers) {
        handlers.orderedStream().forEach(this::register);
    }

    private void register(PaymentResultHandler handler) {
        PayableType type = handler.type();
        if (type == null) {
            throw new IllegalStateException(handler.getClass().getName() + " returned null type()");
        }
        PaymentResultHandler existing = handlers.putIfAbsent(type, handler);
        if (existing != null) {
            throw new IllegalStateException("Duplicate PaymentResultHandler for " + type + ": "
                + existing.getClass().getName() + ", " + handler.getClass().getName());
        }
    }

    /**
     * @throws IllegalStateException 해당 타입의 Handler가 없을 때. 결제를 만들기 전에 막아야 하는 설정 오류다
     */
    public PaymentResultHandler get(PayableType type) {
        PaymentResultHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No PaymentResultHandler registered for " + type);
        }
        return handler;
    }

    public boolean supports(PayableType type) {
        return handlers.containsKey(type);
    }
}
