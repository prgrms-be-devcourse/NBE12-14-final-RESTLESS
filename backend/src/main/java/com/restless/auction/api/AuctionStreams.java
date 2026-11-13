package com.restless.auction.api;

import com.restless.auction.application.AuctionChangedEvent;
import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.application.AuctionQueryService.AuctionSnapshot;
import com.restless.auction.domain.AuctionStatus;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 경매 SSE 연결을 경매별로 들고, 바뀐 경매의 최신 상태를 모아서 보낸다.
 *
 * <ul>
 *   <li>커밋 후({@code AFTER_COMMIT}) {@link AuctionChangedEvent}를 받으면 경매 ID를 "바뀜"으로 표시만 한다.</li>
 *   <li>전용 스레드가 일정 간격(기본 0.2초)마다 바뀐 경매의 상태를 한 번 조회해 그 경매의 연결 전체에 보낸다.
 *       그 사이 입찰이 여러 번 있어도 최신 상태 하나만 간다.</li>
 *   <li>종료(CLOSED) 상태를 보낸 뒤에는 연결을 닫는다. 더 바뀔 일이 없다.</li>
 * </ul>
 *
 * <p>읽기 전용이다. 연결이 끊기거나 전송이 실패해도 입찰과 낙찰에는 영향이 없다 (INV-AU-09).
 * 실패는 로그와 메트릭({@code auction.stream.failure})으로 남긴다.
 *
 * <p>연결은 이 서버 메모리에만 있다. 서버가 여러 대일 때의 전달은 1차 범위 밖이다 (policy.md 9절).
 */
@Component
public class AuctionStreams {

    private static final Logger log = LoggerFactory.getLogger(AuctionStreams.class);
    static final String EVENT_NAME = "auction";

    private final AuctionQueryService queries;
    private final MeterRegistry meters;
    private final long timeoutMs;
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher;

    public AuctionStreams(AuctionQueryService queries, MeterRegistry meters,
                          @Value("${restless.auction.stream.flush-interval-ms:200}") long flushIntervalMs,
                          @Value("${restless.auction.stream.timeout-ms:1800000}") long timeoutMs) {
        this.queries = queries;
        this.meters = meters;
        this.timeoutMs = timeoutMs;
        meters.gauge("auction.stream.connections", subscribers,
                map -> map.values().stream().mapToInt(Set::size).sum());
        // 다른 스케줄러(경매 종료, 결제 대사)와 스레드를 나누지 않아야 0.2초 간격이 밀리지 않는다.
        this.flusher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("auction-stream-flush").daemon().factory());
        flusher.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
        subscribers.clear();
    }

    /**
     * 연결을 등록하고 현재 상태를 한 번 보낸다. 등록을 먼저 해야 조회와 등록 사이의 변경을 놓치지 않는다.
     * 그 변경은 다음 전송에 다시 가고, 클라이언트는 같은 revision을 무시한다.
     */
    public SseEmitter subscribe(Long auctionId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> remove(auctionId, emitter));
        emitter.onTimeout(() -> {
            remove(auctionId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(auctionId, emitter));
        add(auctionId, emitter);

        Optional<AuctionSnapshot> snapshot = queries.snapshot(auctionId);
        if (snapshot.isEmpty()) {
            remove(auctionId, emitter);
            throw new BusinessException(ErrorCode.AUCTION_NOT_FOUND);
        }
        send(auctionId, List.of(emitter), snapshot.get());
        return emitter;
    }

    /** 트랜잭션이 커밋된 뒤에만 불린다. 보고 있는 연결이 있을 때만 표시한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(AuctionChangedEvent event) {
        if (subscribers.containsKey(event.auctionId())) {
            changed.add(event.auctionId());
        }
    }

    /** 바뀐 경매마다 최신 상태를 한 번 조회해 보낸다. 전용 스레드가 부르고, 테스트에서는 직접 부른다. */
    public void flush() {
        for (Long auctionId : new ArrayList<>(changed)) {
            changed.remove(auctionId);
            try {
                flush(auctionId);
            } catch (RuntimeException e) {
                log.error("Auction stream flush failed auctionId={}", auctionId, e);
                meters.counter("auction.stream.failure", "stage", "flush").increment();
            }
        }
    }

    /** 이 경매에 열려 있는 연결 수. */
    public int connections(Long auctionId) {
        Set<SseEmitter> set = subscribers.get(auctionId);
        return set == null ? 0 : set.size();
    }

    private void flush(Long auctionId) {
        Set<SseEmitter> set = subscribers.get(auctionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Optional<AuctionSnapshot> snapshot = queries.snapshot(auctionId);
        if (snapshot.isEmpty()) {
            // 시작 전 경매가 삭제됐다. 보낼 상태가 없으므로 연결을 닫는다.
            List.copyOf(set).forEach(SseEmitter::complete);
            subscribers.remove(auctionId);
            return;
        }
        send(auctionId, List.copyOf(set), snapshot.get());
    }

    private void send(Long auctionId, List<SseEmitter> emitters, AuctionSnapshot snapshot) {
        boolean closed = snapshot.status() == AuctionStatus.CLOSED;
        for (SseEmitter emitter : emitters) {
            try {
                // 빌더는 build()할 때 내용을 덧붙이므로 연결마다 새로 만든다.
                emitter.send(SseEmitter.event()
                        .name(EVENT_NAME)
                        .id(String.valueOf(snapshot.revision()))
                        .data(snapshot, MediaType.APPLICATION_JSON));
                if (closed) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                // 클라이언트가 이미 떠났다. 연결만 정리하고 입찰·낙찰은 그대로 둔다.
                log.warn("Auction stream send failed auctionId={} reason={}", auctionId, e.toString());
                meters.counter("auction.stream.failure", "stage", "send").increment();
                remove(auctionId, emitter);
            }
        }
    }

    /** 스레드가 예외로 멈추면 이후 전송이 모두 끊기므로, 여기서 남기고 다음 주기로 넘어간다. */
    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            log.error("Auction stream flush loop failed", t);
            meters.counter("auction.stream.failure", "stage", "loop").increment();
        }
    }

    private void add(Long auctionId, SseEmitter emitter) {
        subscribers.compute(auctionId, (id, set) -> {
            Set<SseEmitter> target = set == null ? ConcurrentHashMap.newKeySet() : set;
            target.add(emitter);
            return target;
        });
    }

    private void remove(Long auctionId, SseEmitter emitter) {
        subscribers.computeIfPresent(auctionId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
