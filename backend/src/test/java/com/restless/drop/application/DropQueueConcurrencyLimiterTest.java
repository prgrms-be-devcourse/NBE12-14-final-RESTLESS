package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import com.restless.common.ratelimit.RateLimitProperties;
import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("대기열 동시 처리 상한 (#390)")
class DropQueueConcurrencyLimiterTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static final RateLimitProperties.Rule RULE = new RateLimitProperties.Rule(5, Duration.ofSeconds(10));
    private static RateLimitProperties rateLimits(RateLimitProperties.Rule entry, RateLimitProperties.Rule status) {
        return new RateLimitProperties(true, 100, Duration.ofMinutes(2), RULE, RULE, RULE, entry, status);
    }
    private static final RateLimitProperties DEFAULT_LIMITS =
            rateLimits(RULE, new RateLimitProperties.Rule(5, Duration.ofSeconds(1)));

    private DropQueueConcurrencyLimiter limiter(int entry, int status) {
        return new DropQueueConcurrencyLimiter(entry, status, 3, 1, DEFAULT_LIMITS, registry);
    }

    @Test
    @DisplayName("상한까지 받고 넘으면 기다리지 않고 거절한다")
    void rejectsAboveLimit() {
        var limiter = limiter(2, 3);
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isFalse();
        assertThat(registry.counter("drop.queue.busy", "kind", "status").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("진입과 조회는 서로의 허용치를 쓰지 않는다")
    void kindsAreIndependent() {
        var limiter = limiter(1, 1);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isFalse();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
    }

    @Test
    @DisplayName("끝난 요청의 허용치는 다시 받을 수 있다")
    void releaseRestoresPermit() {
        var limiter = limiter(1, 1);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isFalse();
        limiter.release(Kind.ENTRY);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
    }

    @Test
    @DisplayName("진입과 조회의 Retry-After를 따로 준다")
    void retryAfterPerKind() {
        var limiter = limiter(1, 1);
        assertThat(limiter.retryAfterSeconds(Kind.ENTRY)).isEqualTo(3);
        assertThat(limiter.retryAfterSeconds(Kind.STATUS)).isEqualTo(1);
    }

    @Test
    @DisplayName("상한이나 Retry-After가 1 미만이면 설정 검증이 실패한다")
    void invalidSettingsFail() {
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(0, 1, 3, 1, DEFAULT_LIMITS, registry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 0, 3, 1, DEFAULT_LIMITS, registry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 1, 0, 1, DEFAULT_LIMITS, registry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 1, 3, 0, DEFAULT_LIMITS, registry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("진입 Retry-After x 제한 횟수가 제한 창보다 짧으면 설정 검증이 실패한다")
    void retryAfterMustFitRateLimitWindow() {
        // 1초 x 5회 = 5초 < 10초 창. 계약대로 재시도해도 6번째에 429를 받는다.
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 1, 1, 1, DEFAULT_LIMITS, registry))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-after");
        // 2초 x 5회 = 10초 = 창. 경계는 받아들인다.
        assertThat(new DropQueueConcurrencyLimiter(1, 1, 2, 1, DEFAULT_LIMITS, registry).retryAfterSeconds(Kind.ENTRY)).isEqualTo(2);
        // 조회도 같은 규칙이다. 1초 창에 5회 제한이면 1초면 충분하다.
        var strictStatus = rateLimits(RULE, new RateLimitProperties.Rule(2, Duration.ofSeconds(10)));
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 1, 3, 1, strictStatus, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
