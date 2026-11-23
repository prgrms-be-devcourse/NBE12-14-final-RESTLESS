package com.restless.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RequestRateLimiterTest {
    private final RateLimitApiTest.MutableClock clock = new RateLimitApiTest.MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private RateLimitProperties properties(boolean enabled, long maximumSize, int limit) {
        var rule = new RateLimitProperties.Rule(limit, Duration.ofSeconds(10));
        return new RateLimitProperties(enabled, maximumSize, Duration.ofMinutes(1),
                rule, rule, rule, rule, rule);
    }

    @Test
    void concurrentRequestsCannotExceedConfiguredLimit() throws Exception {
        var limiter = new RequestRateLimiter(clock, properties(true, 100, 5), meters);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return limiter.retryAfterSeconds(RequestRateLimiter.RuleName.QUEUE_STATUS, "1");
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Long> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == 0) allowed++;
            }
            assertThat(allowed).isEqualTo(5);
        }
        assertThat(meters.get("restless.ratelimit.rejected").tag("rule", "queue_status")
                .counter().count()).isEqualTo(95);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).hasSize(1));
    }

    @Test
    void manyKeysStayBoundedAndExpireUsingInjectedClock() {
        var limiter = new RequestRateLimiter(clock, properties(true, 3, 5), meters);
        Cache<?, ?> cache = (Cache<?, ?>) ReflectionTestUtils.getField(limiter, "windows");
        for (int i = 0; i < 100; i++) {
            limiter.retryAfterSeconds(RequestRateLimiter.RuleName.LOGIN, "ip-" + i);
            assertThat(cache.estimatedSize()).isLessThanOrEqualTo(3);
        }
        assertThat(cache.estimatedSize()).isPositive();
        clock.advanceMillis(60_000);
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    void disabledLimiterDoesNotStoreKeysOrIncrementRejectedMetric() {
        var limiter = new RequestRateLimiter(clock, properties(false, 3, 1), meters);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.retryAfterSeconds(RequestRateLimiter.RuleName.LOGIN, "ip")).isZero();
        }
        Cache<?, ?> cache = (Cache<?, ?>) ReflectionTestUtils.getField(limiter, "windows");
        assertThat(cache.estimatedSize()).isZero();
        assertThat(meters.get("restless.ratelimit.rejected").tag("rule", "login")
                .counter().count()).isZero();
    }

    @Test
    void invalidSettingsFailStartupRatherThanSilentlyDisableProtection() {
        var rule = new RateLimitProperties.Rule(1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RateLimitProperties.Rule(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Rule(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(true, 0, Duration.ofMinutes(1),
                rule, rule, rule, rule, rule)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(true, 3, Duration.ofSeconds(30),
                rule, rule, rule, rule, rule)).isInstanceOf(IllegalArgumentException.class);
    }
}
