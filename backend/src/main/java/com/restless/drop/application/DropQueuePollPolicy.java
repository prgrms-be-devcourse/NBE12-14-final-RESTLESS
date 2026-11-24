package com.restless.drop.application;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 대기 중인 순번에 맞는 다음 조회 간격(pollAfterMs)을 정한다.
 * 상한은 heartbeat 기록 간격 이하이고, 이탈 판정 시간 안에 조회가 여러 번 들어오도록 설정을 검증한다.
 */
@Component
@EnableConfigurationProperties(DropQueuePollProperties.class)
public class DropQueuePollPolicy {
    /** 이탈 판정 시간 안에 조회가 최소 이 횟수는 들어오게 한다. */
    static final int MIN_POLLS_PER_TIMEOUT = 3;
    static final long DEFAULT_MAX_MS = 10_000;

    private final List<DropQueuePollProperties.Tier> tiers;
    private final long fallbackMs;
    private final long maxMs;

    public DropQueuePollPolicy(DropQueuePollProperties properties, DropQueueSettings settings) {
        this.tiers = List.copyOf(properties.getTiers());
        this.fallbackMs = properties.getFallbackMs();
        this.maxMs = properties.getMaxMs() != null ? properties.getMaxMs()
                : Math.min(DEFAULT_MAX_MS, Math.min(settings.heartbeatInterval().toMillis(),
                        settings.heartbeatTimeout().toMillis() / MIN_POLLS_PER_TIMEOUT));
        validate(settings);
    }

    /** WAITING 순번에 대한 다음 조회 간격. 순번은 1 이상으로 본다. */
    public long afterMs(long position) {
        long value = fallbackMs;
        for (var tier : tiers) {
            if (position <= tier.upToPosition()) {
                value = tier.afterMs();
                break;
            }
        }
        return Math.min(value, maxMs);
    }

    private void validate(DropQueueSettings settings) {
        if (maxMs < 1 || fallbackMs < 1) throw new IllegalArgumentException("Queue poll intervals must be positive");
        long previousUpTo = 0;
        long previousAfter = 0;
        for (var tier : tiers) {
            if (tier.upToPosition() <= previousUpTo)
                throw new IllegalArgumentException("Queue poll tiers must have increasing positions");
            if (tier.afterMs() < 1 || tier.afterMs() < previousAfter)
                throw new IllegalArgumentException("Queue poll tier intervals must be positive and not decrease");
            previousUpTo = tier.upToPosition();
            previousAfter = tier.afterMs();
        }
        if (fallbackMs < previousAfter)
            throw new IllegalArgumentException("Queue poll fallback must not be shorter than the last tier");
        if (maxMs > settings.heartbeatInterval().toMillis())
            throw new IllegalArgumentException("Queue poll max must not exceed the heartbeat interval");
        if (maxMs * MIN_POLLS_PER_TIMEOUT > settings.heartbeatTimeout().toMillis())
            throw new IllegalArgumentException("Queue poll max must allow at least " + MIN_POLLS_PER_TIMEOUT
                    + " polls within the heartbeat timeout");
    }
}
