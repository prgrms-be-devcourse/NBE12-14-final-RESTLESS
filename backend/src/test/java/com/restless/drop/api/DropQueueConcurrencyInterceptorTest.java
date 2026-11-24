package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@DisplayName("대기열 동시 처리 상한 인터셉터 (#390)")
class DropQueueConcurrencyInterceptorTest {
    private static final String ENTRIES = "/api/drops/{dropId}/queue/entries";
    private static final String ME = "/api/drops/{dropId}/queue/me";
    private final DropQueueConcurrencyLimiter limiter =
            new DropQueueConcurrencyLimiter(1, 1, 2, new SimpleMeterRegistry());
    private final DropQueueConcurrencyInterceptor interceptor = new DropQueueConcurrencyInterceptor(limiter);

    private static MockHttpServletRequest request(String method, String pattern) {
        var request = new MockHttpServletRequest(method, pattern.replace("{dropId}", "1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return request;
    }

    @Test
    @DisplayName("상한까지는 통과하고 넘으면 즉시 503 QUEUE_BUSY와 Retry-After를 준다")
    void rejectsImmediatelyAboveLimit() {
        var first = request("GET", ME);
        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();

        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptor.preHandle(request("GET", ME), response, new Object()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_BUSY);
                    assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(503);
                });
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    @DisplayName("끝난 요청은 허용치를 돌려주고, 다시 받는다")
    void completionReleasesPermit() {
        var first = request("POST", ENTRIES);
        interceptor.preHandle(first, new MockHttpServletResponse(), new Object());
        assertThat(limiter.available(Kind.ENTRY)).isZero();
        interceptor.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);
        assertThat(limiter.available(Kind.ENTRY)).isEqualTo(1);
        assertThat(interceptor.preHandle(request("POST", ENTRIES), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("처리 중 예외가 나도 허용치가 새지 않고, 두 번 돌려주지도 않는다")
    void exceptionDoesNotLeakPermit() {
        var first = request("GET", ME);
        interceptor.preHandle(first, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(first, new MockHttpServletResponse(), new Object(), new IllegalStateException("boom"));
        interceptor.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);
        assertThat(limiter.available(Kind.STATUS)).isEqualTo(1);
    }

    @Test
    @DisplayName("거절된 요청은 허용치를 돌려주지 않는다")
    void rejectedRequestDoesNotReleaseOthersPermit() {
        var first = request("GET", ME);
        interceptor.preHandle(first, new MockHttpServletResponse(), new Object());
        var rejected = request("GET", ME);
        assertThatThrownBy(() -> interceptor.preHandle(rejected, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class);
        interceptor.afterCompletion(rejected, new MockHttpServletResponse(), new Object(), null);
        assertThat(limiter.available(Kind.STATUS)).isZero();
    }

    @Test
    @DisplayName("재고 점유, 결제 같은 다른 경로는 상한과 무관하다")
    void otherPathsAreNotLimited() {
        limiter.tryAcquire(Kind.ENTRY);
        limiter.tryAcquire(Kind.STATUS);
        for (String pattern : new String[] {"/api/drops/{dropId}/reservations",
                "/api/drops/{dropId}/reservations/{reservationId}/cancel", "/api/payments/confirm", "/api/drops/{dropId}"}) {
            assertThat(interceptor.preHandle(request("POST", pattern), new MockHttpServletResponse(), new Object()))
                    .as(pattern).isTrue();
        }
        assertThat(interceptor.preHandle(request("POST", ME), new MockHttpServletResponse(), new Object())).isTrue();
    }
}
