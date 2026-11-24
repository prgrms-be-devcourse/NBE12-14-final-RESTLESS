package com.restless.common.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("restless.ratelimit")
public record RateLimitProperties(boolean enabled, long maximumSize, Duration expireAfterAccess,
                                  Rule login, Rule signup, Rule refresh, Rule queueEntry, Rule queueStatus,
                                  Write write) {
    @ConstructorBinding
    public RateLimitProperties {
        if (maximumSize <= 0 || expireAfterAccess == null || expireAfterAccess.isNegative()
                || expireAfterAccess.isZero()) {
            throw new IllegalArgumentException("restless.ratelimit cache size and expiration must be positive");
        }
        if (login == null || signup == null || refresh == null || queueEntry == null || queueStatus == null) {
            throw new IllegalArgumentException("restless.ratelimit requires all five rules");
        }
        List<Rule> rules = new ArrayList<>(List.of(login, signup, refresh, queueEntry, queueStatus));
        if (write != null) {
            rules.addAll(List.of(write.fanMeetingApplication, write.ticketReservation, write.auctionBid,
                    write.dropReservation));
        }
        for (Rule rule : rules) {
            if (expireAfterAccess.compareTo(rule.window()) < 0) {
                throw new IllegalArgumentException("restless.ratelimit expiration must cover every window");
            }
        }
    }

    /** 쓰기 한도 없이 만든다. 쓰기 API는 제한하지 않는다. */
    public RateLimitProperties(boolean enabled, long maximumSize, Duration expireAfterAccess,
                               Rule login, Rule signup, Rule refresh, Rule queueEntry, Rule queueStatus) {
        this(enabled, maximumSize, expireAfterAccess, login, signup, refresh, queueEntry, queueStatus, null);
    }

    /** 몰리는 쓰기 API의 회원별 한도. 통째로 없으면 제한하지 않고, 일부만 있으면 기동하지 않는다. */
    public record Write(Rule fanMeetingApplication, Rule ticketReservation, Rule auctionBid, Rule dropReservation) {
        public Write {
            if (fanMeetingApplication == null || ticketReservation == null || auctionBid == null
                    || dropReservation == null) {
                throw new IllegalArgumentException("restless.ratelimit.write requires all four rules");
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
