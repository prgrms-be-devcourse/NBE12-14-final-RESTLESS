package com.restless.concert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.concert.application.dto.SeatPosition;
import com.restless.concert.infrastructure.LayoutSeatPositionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("배치 좌석 위치 캐시")
class LayoutSeatPositionCacheTest {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final LayoutSeatPositionRepository repository = mock(LayoutSeatPositionRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T00:00:00Z"));

    @Test
    @DisplayName("같은 배치는 유효 시간 동안 한 번만 읽고 배치마다 따로 기억한다")
    void readsEachLayoutOnceWithinTtl() {
        when(repository.findByLayoutId(1L)).thenReturn(List.of(position(10L)));
        when(repository.findByLayoutId(2L)).thenReturn(List.of(position(20L)));
        var cache = cache(true);

        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(10L);
        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(10L);
        assertThat(cache.get(2L)).extracting(SeatPosition::venueSeatId).containsExactly(20L);

        verify(repository, times(1)).findByLayoutId(1L);
        verify(repository, times(1)).findByLayoutId(2L);
    }

    @Test
    @DisplayName("유효 시간이 지나면 DB에서 다시 읽는다")
    void reloadsAfterTtl() {
        when(repository.findByLayoutId(1L)).thenReturn(List.of(position(10L)), List.of(position(11L)));
        var cache = cache(true);

        cache.get(1L);
        clock.advance(TTL.minusMillis(1));
        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(10L);
        clock.advance(Duration.ofMillis(1));
        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(11L);

        verify(repository, times(2)).findByLayoutId(1L);
    }

    @Test
    @DisplayName("꺼져 있으면 요청마다 DB에서 읽는다")
    void readsEveryTimeWhenDisabled() {
        when(repository.findByLayoutId(1L)).thenReturn(List.of(position(10L)));
        var cache = cache(false);

        cache.get(1L);
        cache.get(1L);

        verify(repository, times(2)).findByLayoutId(1L);
    }

    @Test
    @DisplayName("읽기 실패는 기억하지 않고 다음 요청이 다시 읽는다")
    void doesNotCacheLoadFailure() {
        when(repository.findByLayoutId(1L)).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(position(10L)));
        var cache = cache(true);

        assertThatThrownBy(() -> cache.get(1L)).isInstanceOf(IllegalStateException.class).hasMessage("db down");
        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(10L);

        verify(repository, times(2)).findByLayoutId(1L);
    }

    @Test
    @DisplayName("기억한 목록은 바꿀 수 없고 저장소가 준 목록이 바뀌어도 영향받지 않는다")
    void returnsImmutableCopy() {
        var loaded = new ArrayList<>(List.of(position(10L)));
        when(repository.findByLayoutId(1L)).thenReturn(loaded);
        var cache = cache(true);

        var first = cache.get(1L);
        loaded.add(position(99L));

        assertThat(cache.get(1L)).extracting(SeatPosition::venueSeatId).containsExactly(10L);
        assertThatThrownBy(() -> first.add(position(1L))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("같은 배치를 동시에 처음 찾으면 DB는 한 번만 읽는다")
    void loadsOnceForConcurrentFirstRequests() throws Exception {
        var loads = new AtomicInteger();
        var release = new CountDownLatch(1);
        when(repository.findByLayoutId(1L)).thenAnswer(invocation -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return List.of(position(10L));
        });
        var cache = cache(true);
        int callers = 8;
        var ready = new CountDownLatch(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<List<SeatPosition>>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    return cache.get(1L);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            release.countDown();
            for (var result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).extracting(SeatPosition::venueSeatId).containsExactly(10L);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("유효 시간은 0보다 커야 한다")
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new LayoutSeatPositionCache(repository, clock, new SimpleMeterRegistry(), true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LayoutSeatPositionCache cache(boolean enabled) {
        return new LayoutSeatPositionCache(repository, clock, new SimpleMeterRegistry(), enabled, TTL);
    }

    private static SeatPosition position(long venueSeatId) {
        return new SeatPosition(venueSeatId, "A", "1", String.valueOf(venueSeatId), BigDecimal.ONE, BigDecimal.TEN);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
