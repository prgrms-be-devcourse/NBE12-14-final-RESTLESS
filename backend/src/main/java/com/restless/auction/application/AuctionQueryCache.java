package com.restless.auction.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.restless.auction.application.AuctionQueryService.AuctionDetail;
import com.restless.auction.application.AuctionQueryService.AuctionPage;
import com.restless.auction.domain.AuctionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 목록과 상세 조회 결과를 앱 메모리에 보관한다. 트랜잭션 밖에서 호출한다. */
@Slf4j
@Component
public class AuctionQueryCache {

    private final AuctionQueryService queries;
    private final Clock clock;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final Cache<DetailKey, AuctionDetail> details;
    private final Cache<ListKey, AuctionPage> lists;
    private final Cache<Long, Long> generations;

    public AuctionQueryCache(AuctionQueryService queries, Clock clock, MeterRegistry meters,
                             @Value("${restless.auction.query-cache.enabled:true}") boolean enabled,
                             @Value("${restless.auction.query-cache.ttl-ms:1000}") long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("restless.auction.query-cache.ttl-ms must be positive");
        }
        this.queries = queries;
        this.clock = clock;
        this.meters = meters;
        this.enabled = enabled;

        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.details = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS)
                .ticker(ticker)
                .build();
        this.lists = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(ttlMs, TimeUnit.MILLISECONDS)
                .ticker(ticker)
                .build();
        this.generations = Caffeine.newBuilder()
                .expireAfterWrite(generationRetentionMs(ttlMs), TimeUnit.MILLISECONDS)
                .ticker(ticker)
                .build();
    }

    public AuctionPage list(AuctionStatus status, int page, int size) {
        if (!enabled) {
            return queries.list(status, page, size);
        }
        ListKey key = new ListKey(status, page, size);
        return lists.get(key, ignored -> queries.list(status, page, size));
    }

    public AuctionDetail detail(Long auctionId) {
        if (!enabled) {
            return withCurrentServerTime(queries.detail(auctionId));
        }
        long generation = currentGeneration(auctionId);
        DetailKey key = new DetailKey(auctionId, generation);
        AuctionDetail cached = details.get(key, ignored -> queries.detail(auctionId));
        return withCurrentServerTime(cached);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(AuctionChangedEvent event) {
        try {
            generations.asMap().compute(event.auctionId(), (ignored, generation) -> generation == null ? 1 : generation + 1);
        } catch (RuntimeException exception) {
            log.error("Auction query cache invalidation failed auctionId={}", event.auctionId(), exception);
            meters.counter("auction.query.cache.failure", "stage", "invalidate").increment();
            throw exception;
        }
    }

    private AuctionDetail withCurrentServerTime(AuctionDetail detail) {
        return detail.withServerTime(clock.instant());
    }

    private long currentGeneration(Long auctionId) {
        Long generation = generations.getIfPresent(auctionId);
        return generation == null ? 0 : generation;
    }

    private static long generationRetentionMs(long ttlMs) {
        long longerThanQueryTtl = ttlMs > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : ttlMs * 2;
        return Math.max(Duration.ofMinutes(1).toMillis(), longerThanQueryTtl);
    }

    private record DetailKey(Long auctionId, long generation) {
    }

    private record ListKey(AuctionStatus status, int page, int size) {
    }
}
