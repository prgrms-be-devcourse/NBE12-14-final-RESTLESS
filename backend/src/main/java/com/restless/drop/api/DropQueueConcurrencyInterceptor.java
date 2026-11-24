package com.restless.drop.api;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 대기열 진입과 상태 조회에만 동시 처리 상한을 건다. 넘으면 바로 503 QUEUE_BUSY와 Retry-After를 준다.
 * 허용치는 요청이 끝나면 정상 응답, 예외, 취소 어느 경우든 afterCompletion에서 돌려준다.
 */
@Component
public class DropQueueConcurrencyInterceptor implements HandlerInterceptor {
    private static final String ACQUIRED = DropQueueConcurrencyInterceptor.class.getName() + ".acquired";
    private final DropQueueConcurrencyLimiter limiter;

    public DropQueueConcurrencyInterceptor(DropQueueConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) return true;
        Kind kind = select(request.getMethod(), pattern.toString());
        if (kind == null) return true;
        if (!limiter.tryAcquire(kind)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(limiter.retryAfterSeconds()));
            throw new BusinessException(ErrorCode.QUEUE_BUSY);
        }
        request.setAttribute(ACQUIRED, kind);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(ACQUIRED) instanceof Kind kind) {
            request.removeAttribute(ACQUIRED);
            limiter.release(kind);
        }
    }

    private Kind select(String method, String path) {
        if ("POST".equals(method) && "/api/drops/{dropId}/queue/entries".equals(path)) return Kind.ENTRY;
        if (("GET".equals(method) || "HEAD".equals(method)) && "/api/drops/{dropId}/queue/me".equals(path))
            return Kind.STATUS;
        return null;
    }
}
