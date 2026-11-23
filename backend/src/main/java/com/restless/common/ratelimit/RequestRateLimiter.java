package com.restless.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 단일 서버의 메모리 제한이다. 서버가 2대 이상이면 한도가 서버별로 나뉜다.
 * 그때는 공유 저장소나 앞단 제한이 필요하다.
 */
@Component
public class RequestRateLimiter {
    enum RuleName { LOGIN, SIGNUP, REFRESH, QUEUE_ENTRY, QUEUE_STATUS }

    private final Clock clock;
    private final RateLimitProperties properties;
    private final Cache<Key, Window> windows;
    private final Map<RuleName, Counter> rejected = new EnumMap<>(RuleName.class);

    public RequestRateLimiter(Clock clock, RateLimitProperties properties, MeterRegistry meters) {
        this.clock = clock;
        this.properties = properties;
        Instant origin = clock.instant();
        windows = Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfterAccess(properties.expireAfterAccess())
                .ticker(() -> Duration.between(origin, clock.instant()).toNanos())
                .build();
        for (RuleName name : RuleName.values()) {
            rejected.put(name, meters.counter("restless.ratelimit.rejected", "rule",
                    name.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /**
     * 0이면 허용, 양수이면 다음 시도까지의 초다. 첫 요청부터 고정 길이의 창을 센다.
     * 짧은 메모리 연산만 직렬화하여 count 갱신과 새 key의 eviction을 함께 보호한다.
     * 정리 후 반환하므로 Caffeine의 비동기 eviction 동안에도 요청 간 크기 상한을 지킨다.
     */
    synchronized long retryAfterSeconds(RuleName name, String subject) {
        if (!properties.enabled()) return 0;
        RateLimitProperties.Rule rule = rule(name);
        Instant now = clock.instant();
        Key key = new Key(name, subject);
        Window window = windows.getIfPresent(key);
        if (window == null || !now.isBefore(window.endsAt)) {
            window = new Window(now.plus(rule.window()));
            windows.put(key, window);
            windows.cleanUp();
        }
        if (window.count < rule.limit()) {
            window.count++;
            return 0;
        }
        rejected.get(name).increment();
        Duration remaining = Duration.between(now, window.endsAt);
        return remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
    }

    private RateLimitProperties.Rule rule(RuleName name) {
        return switch (name) {
            case LOGIN -> properties.login();
            case SIGNUP -> properties.signup();
            case REFRESH -> properties.refresh();
            case QUEUE_ENTRY -> properties.queueEntry();
            case QUEUE_STATUS -> properties.queueStatus();
        };
    }

    private record Key(RuleName rule, String subject) { }

    private static final class Window {
        private final Instant endsAt;
        private int count;
        private Window(Instant endsAt) { this.endsAt = endsAt; }
    }
}
