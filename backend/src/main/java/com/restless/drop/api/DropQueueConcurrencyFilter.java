package com.restless.drop.api;

import com.restless.common.error.ErrorCode;
import com.restless.common.web.TraceIdFilter;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import com.restless.drop.application.DropSoldOutCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * 대기열 진입과 상태 조회에만 동시 처리 상한을 건다. 넘으면 바로 503 DROP_QUEUE_BUSY와 Retry-After를 준다 (#390, #477).
 *
 * <p>Spring Security, MVC, 공통 예외 처리를 지나기 전에 거절해야 거절 한 건의 CPU가 작다. 판매 첫 20초에
 * 진입 거절이 앱 CPU의 35~43%를 썼다 (#466). 그래서 traceId 필터와 HTTP 관측 필터 바로 뒤, Security 앞에 둔다.
 * HTTP 관측 뒤에 두는 것은 거절 응답도 http.server.requests 메트릭에 남겨 측정을 비교할 수 있게 하려는 것이다.
 *
 * <p>인증과 사용자별 요청 횟수 제한(429)보다 먼저 돈다. 그래서 토큰이 없거나 한도를 넘긴 요청도 허용치가
 * 없으면 503이다. 거절은 어떤 상태도 바꾸지 않는다. 허용치는 요청이 어떻게 끝나든 finally에서 돌려준다.
 *
 * <p>진입은 허용치를 쓰기 전에 드롭 단위 품절 표시({@link DropSoldOutCache})를 먼저 본다 (#481). 판매 중 SKU가
 * 모두 품절인 드롭이면 409 DROP_SOLD_OUT으로 끝낸다. 판매 첫 20초에 품절 409가 앱 CPU의 약 27%를 썼다.
 */
@Component
@Order(DropQueueConcurrencyFilter.ORDER)
public class DropQueueConcurrencyFilter extends OncePerRequestFilter {
    /** TraceIdFilter(가장 앞)와 HTTP 관측 필터(가장 앞 + 1) 뒤, Spring Security(-100) 앞. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final String PREFIX = "/api/drops/";
    private static final String ENTRIES = "/queue/entries";
    private static final String ME = "/queue/me";
    private static final String ENTRIES_PATTERN = "/api/drops/{dropId}/queue/entries";
    private static final String ME_PATTERN = "/api/drops/{dropId}/queue/me";
    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

    private final DropQueueConcurrencyLimiter limiter;
    private final DropSoldOutCache soldOut;
    private final Clock clock;
    private final byte[] busyHead;
    private final byte[] soldOutHead;

    public DropQueueConcurrencyFilter(DropQueueConcurrencyLimiter limiter, DropSoldOutCache soldOut, Clock clock) {
        this.limiter = limiter;
        this.soldOut = soldOut;
        this.clock = clock;
        this.busyHead = head(ErrorCode.DROP_QUEUE_BUSY);
        this.soldOutHead = head(ErrorCode.DROP_SOLD_OUT);
    }

    /** 공통 오류 형식(docs/contracts/api/README.md 3)과 같은 필드 순서. 바뀌는 값은 traceId와 timestamp뿐이다. */
    private static byte[] head(ErrorCode code) {
        return ("{\"code\":\"" + json(code.getCode()) + "\",\"message\":\"" + json(code.getMessage())
                + "\",\"details\":null,\"traceId\":").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = select(request);
        Kind kind = path == null ? null : kind(request.getMethod(), path);
        if (kind == null) {
            chain.doFilter(request, response);
            return;
        }
        if (kind == Kind.ENTRY) {
            Long dropId = dropId(path, ENTRIES);
            if (dropId != null && soldOut.rejects(dropId)) {
                observe(request, kind);
                write(response, ErrorCode.DROP_SOLD_OUT, soldOutHead, null);
                return;
            }
        }
        if (!limiter.tryAcquire(kind)) {
            observe(request, kind);
            write(response, ErrorCode.DROP_QUEUE_BUSY, busyHead, limiter.retryAfterSeconds(kind));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            limiter.release(kind);
        }
    }

    /** 대상 메서드면 디코딩한 경로, 아니면 null. */
    private static String select(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"GET".equals(method) && !"HEAD".equals(method)) return null;
        // MVC와 같은 방식으로 context path를 빼고, URL 인코딩과 세미콜론 내용을 푼 경로로만 판정한다.
        // 원본 URI로 먼저 거르면 /api/drop%73/... 처럼 인코딩한 경로가 상한을 건너뛴다 (#477 검증 D1).
        return PATHS.getPathWithinApplication(request);
    }

    private static Kind kind(String method, String path) {
        if ("POST".equals(method) && isQueuePath(path, ENTRIES)) return Kind.ENTRY;
        if (("GET".equals(method) || "HEAD".equals(method)) && isQueuePath(path, ME)) return Kind.STATUS;
        return null;
    }

    /** 경로의 dropId. 숫자가 아니면 null이고, 형식 오류 응답은 컨트롤러가 정한다. */
    private static Long dropId(String path, String suffix) {
        String id = path.substring(PREFIX.length(), path.length() - suffix.length());
        if (id.isEmpty() || id.length() > 18) return null;
        for (int i = 0; i < id.length(); i++) if (id.charAt(i) < '0' || id.charAt(i) > '9') return null;
        return Long.valueOf(id);
    }

    /** MVC를 지나지 않으므로 HTTP 관측의 uri 태그를 직접 채운다. 비우면 uri=UNKNOWN으로 남는다. */
    private static void observe(HttpServletRequest request, Kind kind) {
        ServerHttpObservationFilter.findObservationContext(request)
                .ifPresent(context -> context.setPathPattern(kind == Kind.ENTRY ? ENTRIES_PATTERN : ME_PATTERN));
    }

    /** {@code /api/drops/{dropId}/queue/...}. dropId는 빈 값이 아니고 '/'가 없는 한 칸이다. 형식 검사는 컨트롤러가 한다. */
    private static boolean isQueuePath(String path, String suffix) {
        if (!path.startsWith(PREFIX) || !path.endsWith(suffix)) return false;
        int idStart = PREFIX.length();
        int idEnd = path.length() - suffix.length();
        return idEnd > idStart && path.indexOf('/', idStart) == idEnd;
    }

    private void write(HttpServletResponse response, ErrorCode code, byte[] head, Integer retryAfter) throws IOException {
        String traceId = TraceIdFilter.current();
        String tail = (traceId == null ? "null" : "\"" + json(traceId) + "\"")
                + ",\"timestamp\":\"" + clock.instant() + "\"}";
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        response.setStatus(code.getHttpStatus().value());
        if (retryAfter != null) response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(head.length + tailBytes.length);
        var out = response.getOutputStream();
        out.write(head);
        out.write(tailBytes);
        out.flush();
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}
