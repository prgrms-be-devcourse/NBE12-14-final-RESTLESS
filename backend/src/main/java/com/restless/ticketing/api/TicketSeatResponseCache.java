package com.restless.ticketing.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.restless.ticketing.application.TicketSeatQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회차별 좌석 조회 응답을 완성된 바이트(JSON, gzip)로 짧게 공유한다 (ADR-0008, #542).
 *
 * <p>응답에는 사용자별 값이 없다. 같은 회차를 유효 시간(기본 0.5초) 안에 찾는 요청은 같은 바이트를 받는다.
 * 같은 회차를 동시에 처음 찾는 요청은 한 번만 계산한다. 계산 실패(없는 회차, 비공개 공연 포함)는 기억하지 않는다.
 * 좌석 상태가 바뀌어도 바로 버리지 않고 유효 시간으로만 따라간다. 선점은 이 응답을 보지 않고 좌석을 잠근 뒤 다시 검증한다.
 */
@Component
public class TicketSeatResponseCache {
    static final int MAXIMUM_PERFORMANCES = 64;

    /** json은 HTTP 응답 본문 그대로, gzip은 그 본문을 압축한 바이트다. */
    public record SeatResponse(byte[] json, byte[] gzip) { }

    private final TicketSeatQueryService seats;
    private final JsonMapper json;
    private final boolean enabled;
    private final Cache<Long, SeatResponse> responses;

    public TicketSeatResponseCache(TicketSeatQueryService seats, JsonMapper json, Clock clock, MeterRegistry registry,
            @Value("${restless.ticketing.seat-response-cache.enabled:true}") boolean enabled,
            @Value("${restless.ticketing.seat-response-cache.ttl:500ms}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("restless.ticketing.seat-response-cache.ttl must be positive");
        }
        this.seats = seats;
        this.json = json;
        this.enabled = enabled;
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.responses = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_PERFORMANCES)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, responses, "ticketing.seat_response");
    }

    public boolean enabled() {
        return enabled;
    }

    /** 꺼져 있으면 호출하지 않는다. 없는 회차·비공개 공연은 기존 예외가 그대로 나간다. */
    public SeatResponse get(Long performanceId) {
        return responses.get(performanceId, this::render);
    }

    private SeatResponse render(Long performanceId) {
        byte[] body = json.writeValueAsBytes(seats.getSeats(performanceId));
        return new SeatResponse(body, gzip(body));
    }

    private static byte[] gzip(byte[] body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length / 4 + 64);
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
