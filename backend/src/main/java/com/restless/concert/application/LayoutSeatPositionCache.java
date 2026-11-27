package com.restless.concert.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.restless.concert.application.dto.PerformanceLayout;
import com.restless.concert.infrastructure.LayoutSeatPositionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 한 대가 기억하는 배치별 좌석 위치 (#508).
 *
 * <p>배치 좌석은 초기 데이터로만 등록되고 오픈된 회차의 배치 원본 수정은 금지된다(venue-consistency.md).
 * 앱에는 쓰기 경로가 없으므로 서버 간 무효화 없이 유효 시간(기본 10분)이 지나면 다시 읽는다.
 * 좌석 상태·등급·가격과 회차 공개 여부는 여기에 두지 않고 요청마다 DB에서 읽는다.
 *
 * <p>같은 배치를 동시에 처음 찾는 요청은 한 번만 DB를 읽고 결과를 나눠 받는다. 읽기 실패는 기억하지 않는다.
 */
@Component
public class LayoutSeatPositionCache {
    static final int MAXIMUM_LAYOUTS = 64;

    private final LayoutSeatPositionRepository positions;
    private final boolean enabled;
    private final Cache<Long, PerformanceLayout> layouts;

    public LayoutSeatPositionCache(LayoutSeatPositionRepository positions, Clock clock, MeterRegistry registry,
            @Value("${restless.concert.layout-cache.enabled:true}") boolean enabled,
            @Value("${restless.concert.layout-cache.ttl:10m}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("restless.concert.layout-cache.ttl must be positive");
        }
        this.positions = positions;
        this.enabled = enabled;
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.layouts = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_LAYOUTS)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, layouts, "concert.layout_seat_positions");
    }

    /** 배치의 모든 좌석 위치와 물리 좌석 ID 색인. 바꿀 수 없다. */
    public PerformanceLayout get(Long layoutId) {
        if (!enabled) {
            return load(layoutId);
        }
        return layouts.get(layoutId, this::load);
    }

    private PerformanceLayout load(Long layoutId) {
        return new PerformanceLayout(layoutId, positions.findByLayoutId(layoutId));
    }
}
