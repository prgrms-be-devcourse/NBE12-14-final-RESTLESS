package com.restless.drop.application;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서버가 알려 주는 다음 조회 간격 설정 (2026-11-24 사용자 위임 결정, #390).
 * 순번이 {@code upToPosition} 이하인 첫 구간의 {@code afterMs}를 쓰고, 어느 구간에도 없으면 {@code fallbackMs}를 쓴다.
 * 어떤 값도 {@code maxMs}를 넘겨 내보내지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties("restless.drop.queue.poll")
public class DropQueuePollProperties {
    private List<Tier> tiers = List.of(new Tier(20, 1000), new Tier(200, 2000), new Tier(1000, 3000));
    private long fallbackMs = 5000;
    /** null이면 min(10000, heartbeat 기록 간격, 이탈 판정 시간 / 3). */
    private Long maxMs;

    public record Tier(long upToPosition, long afterMs) { }
}
