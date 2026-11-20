package com.restless.common.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("restless.ratelimit")
public record RateLimitProperties(boolean enabled, long maximumSize, Duration expireAfterAccess,
                                  Rule login, Rule signup, Rule refresh, Rule queueEntry, Rule queueStatus) {
    public RateLimitProperties {
        if (maximumSize <= 0 || expireAfterAccess == null || expireAfterAccess.isNegative()
                || expireAfterAccess.isZero()) {
            throw new IllegalArgumentException("restless.ratelimit cache size and expiration must be positive");
        }
        if (login == null || signup == null || refresh == null || queueEntry == null || queueStatus == null) {
            throw new IllegalArgumentException("restless.ratelimit requires all five rules");
        }
        for (Rule rule : List.of(login, signup, refresh, queueEntry, queueStatus)) {
            if (expireAfterAccess.compareTo(rule.window()) < 0) {
                throw new IllegalArgumentException("restless.ratelimit expiration must cover every window");
            }
        }
    }

    public record Rule(int limit, Duration window) {
        public Rule {
            if (limit <= 0 || window == null || window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("restless.ratelimit rule limit and window must be positive");
            }
        }
    }
}
