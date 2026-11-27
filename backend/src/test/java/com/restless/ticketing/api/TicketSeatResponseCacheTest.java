package com.restless.ticketing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.ticketing.application.TicketSeatQueryService;
import com.restless.ticketing.application.dto.TicketSeatInfo;
import com.restless.ticketing.domain.TicketSeatStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("좌석 조회 응답 공유 캐시")
class TicketSeatResponseCacheTest {
    private static final Duration TTL = Duration.ofMillis(500);
    private final TicketSeatQueryService seats = mock(TicketSeatQueryService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-28T00:00:00Z"));

    @Test
    @DisplayName("유효 시간 안에는 같은 회차를 한 번만 계산하고 같은 바이트를 준다")
    void sharesBytesWithinTtl() {
        when(seats.getSeats(1L)).thenReturn(List.of(seat(10L, TicketSeatStatus.AVAILABLE)));
        var cache = cache();

        var first = cache.get(1L);
        clock.advance(TTL.minusMillis(1));
        var second = cache.get(1L);

        assertThat(second).isSameAs(first);
        assertThat(new String(first.json())).isEqualTo(json.writeValueAsString(List.of(seat(10L, TicketSeatStatus.AVAILABLE))));
        verify(seats, times(1)).getSeats(1L);
    }

    @Test
    @DisplayName("유효 시간이 지나면 새 상태를 계산한다")
    void reloadsAfterTtl() {
        when(seats.getSeats(1L)).thenReturn(List.of(seat(10L, TicketSeatStatus.AVAILABLE)),
                List.of(seat(10L, TicketSeatStatus.HELD)));
        var cache = cache();

        cache.get(1L);
        clock.advance(TTL);

        assertThat(new String(cache.get(1L).json())).contains("\"HELD\"");
        verify(seats, times(2)).getSeats(1L);
    }

    @Test
    @DisplayName("gzip 바이트는 같은 JSON 본문을 압축한 것이다")
    void gzipMatchesJson() throws Exception {
        when(seats.getSeats(1L)).thenReturn(List.of(seat(10L, TicketSeatStatus.AVAILABLE), seat(11L, TicketSeatStatus.SOLD)));
        var response = cache().get(1L);

        try (var in = new GZIPInputStream(new ByteArrayInputStream(response.gzip()))) {
            assertThat(in.readAllBytes()).isEqualTo(response.json());
        }
    }

    @Test
    @DisplayName("없는 회차 같은 실패는 기억하지 않는다")
    void doesNotCacheFailure() {
        when(seats.getSeats(1L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND))
                .thenReturn(List.of(seat(10L, TicketSeatStatus.AVAILABLE)));
        var cache = cache();

        assertThatThrownBy(() -> cache.get(1L)).isInstanceOf(BusinessException.class);
        assertThat(cache.get(1L).json()).isNotEmpty();
        verify(seats, times(2)).getSeats(1L);
    }

    @Test
    @DisplayName("같은 회차를 동시에 처음 찾으면 한 번만 계산한다")
    void loadsOnceForConcurrentFirstRequests() throws Exception {
        var loads = new AtomicInteger();
        var release = new CountDownLatch(1);
        when(seats.getSeats(1L)).thenAnswer(invocation -> {
            loads.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return List.of(seat(10L, TicketSeatStatus.AVAILABLE));
        });
        var cache = cache();
        int callers = 8;
        var ready = new CountDownLatch(callers);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<TicketSeatResponseCache.SeatResponse>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    return cache.get(1L);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            release.countDown();
            var first = results.get(0).get(5, TimeUnit.SECONDS);
            for (var result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(first);
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("유효 시간은 0보다 커야 한다")
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new TicketSeatResponseCache(seats, json, clock, new SimpleMeterRegistry(), true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TicketSeatResponseCache cache() {
        return new TicketSeatResponseCache(seats, json, clock, new SimpleMeterRegistry(), true, TTL);
    }

    private static TicketSeatInfo seat(long id, TicketSeatStatus status) {
        return new TicketSeatInfo(id, id + 100, "A", "1", String.valueOf(id), new BigDecimal("10.25"), new BigDecimal("-5.25"),
                "VIP", 100000, status);
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
