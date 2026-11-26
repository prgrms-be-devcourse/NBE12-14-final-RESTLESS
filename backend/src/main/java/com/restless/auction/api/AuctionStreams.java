package com.restless.auction.api;

import com.restless.auction.application.AuctionChangedEvent;
import com.restless.auction.application.AuctionQueryCache;
import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.application.AuctionQueryService.AuctionSnapshot;
import com.restless.auction.domain.AuctionStatus;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 *   <li>서버 전체 연결 수에 상한을 둔다 (#244). 넘으면 새 연결만 {@code AUCTION_STREAM_BUSY}로 거절한다.
 *       연결마다 힙을 쓰므로, 상한이 없으면 관전자가 몰릴 때 앱 전체가 메모리 부족으로 멈춘다.</li>
 *   <li>같은 스레드가 일정 간격(기본 15초)마다 모든 연결에 주석을 보낸다 (#447). 서버는 써 봐야 떠난 클라이언트를 알 수 있으므로,
 *       입찰이 없는 경매에서도 떠난 연결을 정리한다.</li>
 *   <li>별도 스레드가 일정 간격(기본 0.5초)마다 이 서버에서 관전 중인 경매의 revision을 DB에서 읽는다 (ADR-0007).
 *       마지막으로 보낸 revision보다 크면 다른 서버가 바꾼 것이므로 "바뀜"으로 표시하고 이 서버의 조회 캐시도 버린다.
 *       이벤트는 이 서버 안에서만 돌기 때문이다. 없어진 경매(삭제)도 "바뀜"으로 표시해 다음 전송이 연결을 닫게 한다.</li>
 * </ul>
 *
 * <p>읽기 전용이다. 연결이 끊기거나 전송이 실패해도 입찰과 낙찰에는 영향이 없다 (INV-AU-09).
 * 실패는 로그와 메트릭({@code auction.stream.failure})으로 남긴다.
 *
 * <p>연결은 이 서버 메모리에만 있다. 다른 서버에서 받은 변경은 위의 revision 확인으로 최대 그 간격만큼 늦게 전달된다.
 */
@Component
public class AuctionStreams {

    private static final Logger log = LoggerFactory.getLogger(AuctionStreams.class);
    static final String EVENT_NAME = "auction";
    /** revision을 한 번에 읽는 경매 수. IN 목록이 너무 길어지지 않게 나눈다. */
    static final int REVISION_BATCH = 500;

    private final AuctionQueryService queries;
    private final AuctionQueryCache queryCache;
    private final MeterRegistry meters;
    private final JsonMapper json;
    private final long timeoutMs;
    private final int maxConnections;
    private final int retryAfterSeconds;
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** 등록된 연결 수와 등록 중인 자리. 상한 확인과 자리 확보를 한 번에 하려고 맵과 따로 센다. */
    private final AtomicInteger total = new AtomicInteger();
    private final Counter rejected;
    private final Counter heartbeatRemoved;
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    /** 경매별로 그 경매의 모든 연결에 마지막으로 보낸 revision. 다른 서버의 변경을 알아보는 기준이다. */
    private final Map<Long, Long> sentRevisions = new ConcurrentHashMap<>();
    /** revision 확인이 "바뀜"으로 표시한 수. 다른 서버의 변경과, 아직 전송 전인 이 서버의 변경이 함께 들어간다. */
    private final Counter pollChanged;
    private final ScheduledExecutorService flusher;
    /** revision 확인 전용. DB가 느려도 이 서버 변경의 전송(flusher)이 밀리지 않게 스레드를 나눈다. 간격이 0이면 없다. */
    private final ScheduledExecutorService revisionPoller;

    public AuctionStreams(AuctionQueryService queries, AuctionQueryCache queryCache, MeterRegistry meters, JsonMapper json,
                          @Value("${restless.auction.stream.flush-interval-ms:200}") long flushIntervalMs,
                          @Value("${restless.auction.stream.timeout-ms:1800000}") long timeoutMs,
                          @Value("${restless.auction.stream.max-connections:6000}") int maxConnections,
                          @Value("${restless.auction.stream.retry-after-seconds:5}") int retryAfterSeconds,
                          @Value("${restless.auction.stream.heartbeat-interval-ms:15000}") long heartbeatIntervalMs,
                          @Value("${restless.auction.stream.revision-poll-interval-ms:500}") long revisionPollIntervalMs) {
        if (maxConnections < 1 || retryAfterSeconds < 1 || heartbeatIntervalMs < 1) {
            throw new IllegalStateException("restless.auction.stream.max-connections, retry-after-seconds, "
                    + "heartbeat-interval-ms must be positive");
        }
        if (revisionPollIntervalMs < 0) {
            throw new IllegalStateException("restless.auction.stream.revision-poll-interval-ms must be 0 (off) or positive");
        }
        this.queries = queries;
        this.queryCache = queryCache;
        this.meters = meters;
        this.json = json;
        this.timeoutMs = timeoutMs;
        this.maxConnections = maxConnections;
        this.retryAfterSeconds = retryAfterSeconds;
        meters.gauge("auction.stream.connections", subscribers,
                map -> map.values().stream().mapToInt(Set::size).sum());
        // 측정 전부터 0으로 보이도록 미리 등록한다.
        this.rejected = meters.counter("auction.stream.rejected");
        this.heartbeatRemoved = meters.counter("auction.stream.heartbeat.removed");
        this.pollChanged = meters.counter("auction.stream.poll.changed");
        // 다른 스케줄러(경매 종료, 미완료 결제 재확인 작업)와 스레드를 나누지 않아야 0.2초 간격이 밀리지 않는다.
        this.flusher = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("auction-stream-flush").daemon().factory());
        flusher.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        // 전송과 같은 스레드에서 보내므로 한 연결에 두 스레드가 동시에 쓰지 않는다.
        flusher.scheduleWithFixedDelay(this::heartbeatSafely, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        if (revisionPollIntervalMs > 0) {
            this.revisionPoller = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("auction-stream-revision-poll").daemon().factory());
            revisionPoller.scheduleWithFixedDelay(this::pollRevisionsSafely, revisionPollIntervalMs, revisionPollIntervalMs,
                    TimeUnit.MILLISECONDS);
        } else {
            this.revisionPoller = null;
        }
    }

    @PreDestroy
    void shutdown() {
        if (revisionPoller != null) {
            revisionPoller.shutdownNow();
        }
        flusher.shutdownNow();
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
        subscribers.clear();
        total.set(0);
    }

    /**
     * 연결을 등록하고 현재 상태를 한 번 보낸다. 등록을 먼저 해야 조회와 등록 사이의 변경을 놓치지 않는다.
     * 그 변경은 다음 전송에 다시 가고, 클라이언트는 같은 revision을 무시한다.
     *
     * <p>상한에 닿았으면 연결을 만들지 않고 {@code AUCTION_STREAM_BUSY}를 던진다. 재시도 간격은 {@link #retryAfterSeconds()}.
     */
    public SseEmitter subscribe(Long auctionId) {
        // 확인과 확보를 한 번에 해야 동시에 들어온 요청이 함께 상한을 넘지 않는다.
        if (total.incrementAndGet() > maxConnections) {
            total.decrementAndGet();
            rejected.increment();
            throw new BusinessException(ErrorCode.AUCTION_STREAM_BUSY);
        }
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onCompletion(() -> remove(auctionId, emitter));
        emitter.onTimeout(() -> {
            remove(auctionId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(auctionId, emitter));
        // 확보한 자리는 여기서부터 remove()가 돌려준다.
        add(auctionId, emitter);

        Optional<AuctionSnapshot> snapshot = queries.snapshot(auctionId);
        if (snapshot.isEmpty()) {
            remove(auctionId, emitter);
            throw new BusinessException(ErrorCode.AUCTION_NOT_FOUND);
        }
        try {
            send(auctionId, List.of(emitter), snapshot.get());
        } catch (RuntimeException exception) {
            remove(auctionId, emitter);
            throw exception;
        }
        // 이 경매의 첫 연결이면 기준을 잡는다. 이미 있으면 그대로 둔다: 먼저 붙은 연결은 아직 그 값까지만 받았을 수 있다.
        sentRevisions.putIfAbsent(auctionId, snapshot.get().revision());
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

    /**
     * 이 서버에서 관전 중인 경매의 revision을 DB에서 읽어, 마지막으로 보낸 것보다 크면 "바뀜"으로 표시한다 (ADR-0007).
     * 다른 서버에서 받은 입찰·시작·종료·수정은 이 서버에 이벤트가 오지 않으므로 이렇게 알아낸다.
     * 전용 스레드가 부르고, 테스트에서는 직접 부른다.
     *
     * @return 이번에 "바뀜"으로 표시한 경매 ID
     */
    public Set<Long> pollRevisions() {
        Set<Long> watched = new HashSet<>(subscribers.keySet());
        // 연결이 모두 떠난 경매의 기준은 버린다. 그 사이 새로 붙은 경매가 빠져도 다음 확인에서 한 번 더 보낼 뿐이다.
        sentRevisions.keySet().retainAll(watched);
        Set<Long> marked = new HashSet<>();
        if (watched.isEmpty()) {
            return marked;
        }
        List<Long> ids = List.copyOf(watched);
        for (int from = 0; from < ids.size(); from += REVISION_BATCH) {
            List<Long> batch = ids.subList(from, Math.min(from + REVISION_BATCH, ids.size()));
            Map<Long, Long> current = queries.revisions(batch);
            for (Long auctionId : batch) {
                Long revision = current.get(auctionId);
                Long sent = sentRevisions.get(auctionId);
                if (revision != null && sent != null && revision <= sent) {
                    continue;
                }
                // 이 서버의 변경이 아직 전송 전이어도 여기에 걸린다. 같은 경매를 한 번 더 표시할 뿐이라 괜찮다.
                changed.add(auctionId);
                queryCache.invalidate(auctionId);
                pollChanged.increment();
                marked.add(auctionId);
            }
        }
        return marked;
    }

    /** 이 경매에 열려 있는 연결 수. */
    public int connections(Long auctionId) {
        Set<SseEmitter> set = subscribers.get(auctionId);
        return set == null ? 0 : set.size();
    }

    /** 상한과 비교하는 서버 전체 연결 수. */
    public int totalConnections() {
        return total.get();
    }

    /** 상한에 닿아 거절할 때 응답의 Retry-After(초). */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 모든 연결에 내용 없는 주석({@code :heartbeat})을 보낸다. 전용 스레드가 부르고, 테스트에서는 직접 부른다.
     *
     * <p>클라이언트가 탭을 닫으면 운영체제끼리는 연결 종료를 주고받지만 이 객체에는 알림이 오지 않는다.
     * 그 연결에 써야 실패로 알 수 있다. 종료 직후의 첫 쓰기는 성공할 수 있어 보통 한두 번째 heartbeat에서 정리된다.
     * 주석은 클라이언트의 메시지 처리에 들어가지 않는다.
     */
    public void heartbeat() {
        int removed = 0;
        for (Map.Entry<Long, Set<SseEmitter>> entry : subscribers.entrySet()) {
            for (SseEmitter emitter : List.copyOf(entry.getValue())) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    // 클라이언트가 이미 떠났다. 응답 정리는 컨테이너가 오류 콜백으로 마친다.
                    if (remove(entry.getKey(), emitter)) {
                        removed++;
                    }
                }
            }
        }
        if (removed > 0) {
            // 떠난 연결은 정상 상황이라 연결마다 남기지 않고 한 번에 남긴다.
            log.info("Auction stream heartbeat removed {} closed connections", removed);
            heartbeatRemoved.increment(removed);
        }
    }

    private void flush(Long auctionId) {
        Set<SseEmitter> set = subscribers.get(auctionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        Optional<AuctionSnapshot> snapshot = queries.snapshot(auctionId);
        if (snapshot.isEmpty()) {
            // 시작 전 경매가 삭제됐다. 보낼 상태가 없으므로 연결을 닫는다.
            // 자리를 바로 돌려주려고 목록에서 먼저 뺀다. 완료 콜백은 나중에 오고 그때는 아무것도 하지 않는다.
            for (SseEmitter emitter : List.copyOf(set)) {
                remove(auctionId, emitter);
                emitter.complete();
            }
            return;
        }
        send(auctionId, List.copyOf(set), snapshot.get());
        // 연결 전부에 이 revision까지 보냈다. 기준은 줄지 않는다 (늦게 끝난 조회가 더 낮은 값을 들고 올 수 있다).
        sentRevisions.merge(auctionId, snapshot.get().revision(), Math::max);
    }

    private void send(Long auctionId, List<SseEmitter> emitters, AuctionSnapshot snapshot) {
        boolean closed = snapshot.status() == AuctionStatus.CLOSED;
        String jsonText = json.writeValueAsString(snapshot);
        for (SseEmitter emitter : emitters) {
            try {
                // 빌더는 build()할 때 내용을 덧붙이므로 연결마다 새로 만든다.
                emitter.send(SseEmitter.event()
                        .name(EVENT_NAME)
                        .id(String.valueOf(snapshot.revision()))
                        .data(jsonText, MediaType.APPLICATION_JSON));
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

    private void pollRevisionsSafely() {
        try {
            pollRevisions();
        } catch (Throwable t) {
            // DB가 잠깐 안 되면 다음 주기에 다시 본다. 다른 서버의 변경만 그만큼 늦게 간다.
            log.error("Auction stream revision poll failed", t);
            meters.counter("auction.stream.failure", "stage", "poll").increment();
        }
    }

    private void heartbeatSafely() {
        try {
            heartbeat();
        } catch (Throwable t) {
            log.error("Auction stream heartbeat loop failed", t);
            meters.counter("auction.stream.failure", "stage", "heartbeat").increment();
        }
    }

    private void add(Long auctionId, SseEmitter emitter) {
        subscribers.compute(auctionId, (id, set) -> {
            Set<SseEmitter> target = set == null ? ConcurrentHashMap.newKeySet() : set;
            target.add(emitter);
            return target;
        });
    }

    /**
     * 목록에서 빼고 자리를 돌려준다. 한 연결에 완료·오류·전송 실패가 겹쳐 여러 번 불려도 자리는 한 번만 돌려준다.
     *
     * @return 이번 호출이 실제로 뺐으면 true
     */
    private boolean remove(Long auctionId, SseEmitter emitter) {
        AtomicBoolean removed = new AtomicBoolean();
        subscribers.computeIfPresent(auctionId, (id, set) -> {
            removed.set(set.remove(emitter));
            return set.isEmpty() ? null : set;
        });
        if (removed.get()) {
            total.decrementAndGet();
        }
        return removed.get();
    }
}
