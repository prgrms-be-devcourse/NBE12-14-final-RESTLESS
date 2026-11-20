package com.restless.common.ratelimit;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RequestRateLimiter limiter;

    public RateLimitInterceptor(RequestRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // MVC가 선택한 pattern을 사용하여 context path와 URL 인코딩에 따른 우회를 피한다.
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) return true;
        RequestRateLimiter.RuleName rule = selectRule(request.getMethod(), pattern.toString());
        if (rule == null) return true;
        String subject = switch (rule) {
            case QUEUE_ENTRY, QUEUE_STATUS -> memberId();
            // 프록시나 ALB 뒤에서는 모두 프록시 IP로 보여 전원이 함께 막힐 수 있다.
            // 그때 신뢰할 프록시에 대한 전달 헤더 설정이 필요하다. 임의 X-Forwarded-For는 쓰지 않는다.
            default -> request.getRemoteAddr();
        };
        long retryAfter = limiter.retryAfterSeconds(rule, subject);
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    private RequestRateLimiter.RuleName selectRule(String method, String path) {
        if ("POST".equals(method)) {
            return switch (path) {
                case "/api/auth/login" -> RequestRateLimiter.RuleName.LOGIN;
                case "/api/auth/signup" -> RequestRateLimiter.RuleName.SIGNUP;
                case "/api/auth/refresh" -> RequestRateLimiter.RuleName.REFRESH;
                case "/api/drops/{dropId}/queue/entries" -> RequestRateLimiter.RuleName.QUEUE_ENTRY;
                default -> null;
            };
        }
        // Spring MVC는 GetMapping에 HEAD도 연결한다. 같은 사용자 counter로 heartbeat 쓰기를 보호한다.
        return ("GET".equals(method) || "HEAD".equals(method))
                && "/api/drops/{dropId}/queue/me".equals(path)
                ? RequestRateLimiter.RuleName.QUEUE_STATUS : null;
    }

    /** LoginMemberArgumentResolver와 동일하게 JWT subject를 Long으로 해석한다. */
    private String memberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return Long.valueOf(jwt.getSubject()).toString();
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
