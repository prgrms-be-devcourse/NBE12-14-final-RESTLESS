package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.restless.common.error.ErrorCode;
import com.restless.common.ratelimit.RateLimitProperties;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;

@DisplayName("대기열 동시 처리 상한 필터 (#390, #477)")
class DropQueueConcurrencyFilterTest {
    private static final String ENTRIES = "/api/drops/1/queue/entries";
    private static final String ME = "/api/drops/1/queue/me";
    private static final RateLimitProperties.Rule RULE = new RateLimitProperties.Rule(5, Duration.ofSeconds(10));
    private static final RateLimitProperties LIMITS = new RateLimitProperties(true, 100, Duration.ofMinutes(2),
            RULE, RULE, RULE, RULE, new RateLimitProperties.Rule(5, Duration.ofSeconds(1)));
    private static final Instant NOW = Instant.parse("2026-11-26T05:00:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DropQueueConcurrencyLimiter limiter = new DropQueueConcurrencyLimiter(1, 1, 3, 1, LIMITS, registry);
    private final DropQueueConcurrencyFilter filter =
            new DropQueueConcurrencyFilter(limiter, Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    /** 다음 필터에 들어간 동안의 허용치를 기록한다. */
    private FilterChain recording(Kind kind, AtomicInteger seen) {
        return (req, res) -> seen.set(limiter.available(kind));
    }

    @Test
    @DisplayName("허용치를 쥔 동안 다음 필터로 넘기고, 끝나면 돌려준다")
    void holdsPermitDuringChainAndReleases() throws Exception {
        AtomicInteger during = new AtomicInteger(-1);
        filter.doFilter(request("POST", ENTRIES), new MockHttpServletResponse(), recording(Kind.ENTRY, during));
        assertThat(during.get()).isZero();
        assertThat(limiter.available(Kind.ENTRY)).isEqualTo(1);
    }

    @Test
    @DisplayName("상한을 넘으면 다음 필터를 부르지 않고 공통 오류 형식의 503과 Retry-After를 쓴다")
    void rejectsBeforeChainAboveLimit() throws Exception {
        limiter.tryAcquire(Kind.STATUS);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request("GET", ME), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("DROP_QUEUE_BUSY");
        assertThat(body.get("message").asString()).isEqualTo(ErrorCode.DROP_QUEUE_BUSY.getMessage());
        assertThat(body.get("details").isNull()).isTrue();
        assertThat(body.has("traceId")).isTrue();
        assertThat(body.get("timestamp").asString()).isEqualTo("2026-11-26T05:00:00Z");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
        assertThat(registry.counter("drop.queue.busy", "kind", "status").count()).isEqualTo(1.0);

        // 거절된 요청은 남의 허용치를 돌려주지 않는다.
        assertThat(limiter.available(Kind.STATUS)).isZero();
    }

    @Test
    @DisplayName("진입은 진입 Retry-After를 쓰고 조회 허용치와 따로 센다")
    void entryUsesItsOwnPermitAndRetryAfter() throws Exception {
        limiter.tryAcquire(Kind.ENTRY);
        var response = new MockHttpServletResponse();
        filter.doFilter(request("POST", ENTRIES), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");

        var status = new MockHttpServletResponse();
        filter.doFilter(request("HEAD", ME), status, new MockFilterChain());
        assertThat(status.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("다음 필터가 예외로 끝나도 허용치가 새지 않는다")
    void exceptionDoesNotLeakPermit() {
        FilterChain failing = (req, res) -> { throw new IllegalStateException("boom"); };
        assertThatThrownBy(() -> filter.doFilter(request("GET", ME), new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(limiter.available(Kind.STATUS)).isEqualTo(1);
    }

    @Test
    @DisplayName("인코딩과 세미콜론으로 경로를 바꿔도 같은 상한을 받는다")
    void encodedPathsAreLimited() throws Exception {
        limiter.tryAcquire(Kind.ENTRY);
        for (String uri : new String[] {"/api/drops/1/queue/%65ntries", "/api/drops/1;x=1/queue/entries",
                "/api/drop%73/1/queue/entries", "/%61pi/drops/1/queue/entries", "/api/%64rops/1/queue/entries"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("POST", uri), response, new MockFilterChain());
            assertThat(response.getStatus()).as(uri).isEqualTo(503);
        }
    }

    @Test
    @DisplayName("인코딩한 접두어로 조회해도 같은 상한을 받는다 (#477 검증 D1)")
    void encodedPrefixStatusIsLimited() throws Exception {
        limiter.tryAcquire(Kind.STATUS);
        for (String method : new String[] {"GET", "HEAD"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(method, "/%61pi/drops/1/queue/me"), response, new MockFilterChain());
            assertThat(response.getStatus()).as(method).isEqualTo(503);
        }
    }

    @Test
    @DisplayName("거절할 때 HTTP 관측의 경로 패턴을 채워 uri 태그가 UNKNOWN이 되지 않게 한다")
    void rejectionSetsObservedPathPattern() throws Exception {
        limiter.tryAcquire(Kind.ENTRY);
        var request = request("POST", ENTRIES);
        var response = new MockHttpServletResponse();
        var context = new ServerRequestObservationContext(request, response);
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(context.getPathPattern()).isEqualTo("/api/drops/{dropId}/queue/entries");
    }

    @Test
    @DisplayName("재고 점유, 결제, 다른 메서드와 다른 깊이의 경로는 상한과 무관하다")
    void otherPathsAreNotLimited() throws Exception {
        limiter.tryAcquire(Kind.ENTRY);
        limiter.tryAcquire(Kind.STATUS);
        String[][] cases = {{"POST", "/api/drops/1/reservations"}, {"POST", "/api/drops/1/reservations/2/cancel"},
                {"POST", "/api/payments/confirm"}, {"GET", "/api/drops/1"}, {"POST", ME}, {"GET", ENTRIES},
                {"DELETE", ENTRIES}, {"POST", "/api/drops//queue/entries"}, {"POST", "/api/drops/1/2/queue/entries"}};
        for (String[] c : cases) {
            var chain = new MockFilterChain();
            filter.doFilter(request(c[0], c[1]), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(c[0] + " " + c[1]).isNotNull();
        }
    }
}
