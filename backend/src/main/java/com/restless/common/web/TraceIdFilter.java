package com.restless.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId를 만들어 로그(MDC)와 응답 헤더에 넣는다.
 * 오류 응답 본문의 traceId도 이 값이라, 클라이언트가 알려 준 traceId로 서버 로그를 찾을 수 있다.
 * Security 필터보다 먼저 돌아야 401, 403 응답에도 traceId가 들어가므로 가장 앞 순서로 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 클라이언트가 보낸 값은 로그에 그대로 찍히므로 받지 않고 서버가 만든다.
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 지금 요청의 traceId. 요청 밖(스케줄러 등)에서는 null이다. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
