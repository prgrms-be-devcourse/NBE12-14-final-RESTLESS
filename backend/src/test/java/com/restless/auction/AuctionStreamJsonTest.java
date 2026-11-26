package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.api.AuctionStreams;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.AuctionQueryCache;
import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.application.AuctionQueryService.AuctionSnapshot;
import com.restless.auction.application.BidService;
import com.restless.auth.infrastructure.JwtTokenProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSE 전송 한 번에 상태 JSON을 한 번만 만든다. 받는 내용은 지금과 같아야 한다.
 * 자동 전송 간격을 늘리고 {@link AuctionStreams#flush()}를 직접 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.docker.compose.enabled=false",
    "restless.auction.stream.flush-interval-ms=3600000",
    // AuctionStreamTest와 Spring 컨텍스트를 같이 쓰려고 설정을 맞춘다.
    "restless.auction.stream.revision-poll-interval-ms=0",
})
@Import(TestcontainersConfiguration.class)
class AuctionStreamJsonTest {
    @LocalServerPort int port;
    @Autowired AuctionStreams streams;
    @Autowired BidService bidService;
    @Autowired AuctionCloseService closeService;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean JsonMapper json;
    @MockitoSpyBean AuctionQueryService queries;
    // AuctionStreamTest와 Spring 컨텍스트를 같이 쓰려고 같은 spy를 둔다.
    @MockitoSpyBean AuctionQueryCache queryCache;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Stream<String>> opened = new ArrayList<>();
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;

    record Event(String name, String id, String data) {
        static final Event END = new Event("END", null, null);
    }

    @BeforeEach void setUp() {
        streams.flush();
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        jdbc.update("UPDATE users SET nickname = '한글입찰자' WHERE id = ?", chulsoo);
        f.credit(chulsoo, 100_000);
        f.credit(younghee, 100_000);
    }

    @AfterEach void closeStreams() {
        opened.forEach(Stream::close);
    }

    HttpRequest request(Long auctionId, Long userId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auctions/" + auctionId + "/stream"))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + tokenProvider.issue(userId, "USER").value())
            // 첫 이벤트가 없으면 응답 헤더도 오지 않으므로, 테스트가 멈추지 않게 제한한다.
            .timeout(Duration.ofSeconds(10))
            .GET().build();
    }

    BlockingQueue<Event> connect(Long userId, Long auctionId) throws Exception {
        HttpResponse<Stream<String>> response = http.send(request(auctionId, userId), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        opened.add(response.body());
        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            String name = null;
            String id = null;
            try {
                for (String line : (Iterable<String>) response.body()::iterator) {
                    if (line.startsWith("event:")) name = line.substring(6);
                    else if (line.startsWith("id:")) id = line.substring(3);
                    else if (line.startsWith("data:")) queue.add(new Event(name, id, line.substring(5)));
                }
            } catch (RuntimeException ignored) {
                // 테스트가 연결을 닫으면 읽기가 끊긴다.
            }
            queue.add(Event.END);
        });
        return queue;
    }

    static Event next(BlockingQueue<Event> queue) throws InterruptedException {
        Event event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event).as("5초 안에 메시지가 와야 한다").isNotNull();
        return event;
    }

    void bid(Long userId, Long auctionId, long amount) {
        bidService.place(userId, auctionId, amount, UUID.randomUUID().toString());
    }

    double flushFailures() {
        Counter counter = meters.find("auction.stream.failure").tag("stage", "flush").counter();
        return counter == null ? 0 : counter.count();
    }

    void failSerialization(Long auctionId) {
        doThrow(new IllegalStateException("serialization failed")).when(json).writeValueAsString(
            argThat(value -> value instanceof AuctionSnapshot snapshot && snapshot.auctionId().equals(auctionId)));
    }

    @Test void 연결이_여러_개여도_한_번의_전송에서_JSON은_한_번만_만든다() throws Exception {
        Long auctionId = f.openAuction(seller);
        List<BlockingQueue<Event>> viewers = List.of(connect(chulsoo, auctionId), connect(younghee, auctionId),
            connect(seller, auctionId));
        for (BlockingQueue<Event> viewer : viewers) {
            next(viewer);
        }
        bid(chulsoo, auctionId, 50_000);
        // send()는 이 값을 직접 읽지 않으므로, 호출 횟수가 곧 JSON으로 바꾼 횟수다.
        AuctionSnapshot snapshot = spy(queries.snapshot(auctionId).orElseThrow());
        doReturn(Optional.of(snapshot)).when(queries).snapshot(auctionId);
        clearInvocations(json);

        streams.flush();

        String first = next(viewers.get(0)).data();
        assertThat(next(viewers.get(1)).data()).isEqualTo(first);
        assertThat(next(viewers.get(2)).data()).isEqualTo(first);
        verify(json, times(1)).writeValueAsString(any(AuctionSnapshot.class));
        verify(snapshot, times(1)).minimumBidAmount();
    }

    @Test void 전송_내용은_상태_JSON과_같고_한글이_깨지지_않는다() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<Event> viewer = connect(younghee, auctionId);
        next(viewer);
        bid(chulsoo, auctionId, 50_000);

        streams.flush();

        Event event = next(viewer);
        AuctionSnapshot snapshot = queries.snapshot(auctionId).orElseThrow();
        assertThat(event.name()).isEqualTo("auction");
        assertThat(event.id()).isEqualTo(String.valueOf(snapshot.revision()));
        assertThat(event.data()).isEqualTo(json.writeValueAsString(snapshot)).contains("한글입찰자");
    }

    @Test void 연결_직후_보내는_현재_상태도_같은_형식이다() throws Exception {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 50_000);

        Event event = next(connect(younghee, auctionId));

        assertThat(event.data()).isEqualTo(json.writeValueAsString(queries.snapshot(auctionId).orElseThrow()));
    }

    @Test void 종료_상태도_같은_형식으로_보내고_연결을_닫는다() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<Event> viewer = connect(younghee, auctionId);
        next(viewer);
        bid(chulsoo, auctionId, 50_000);
        f.expireNow(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();

        streams.flush();

        Event closed = next(viewer);
        assertThat(closed.data()).isEqualTo(json.writeValueAsString(queries.snapshot(auctionId).orElseThrow()))
            .contains("\"status\":\"CLOSED\"", "한글입찰자");
        assertThat(next(viewer)).isEqualTo(Event.END);
        f.assertInvariants();
    }

    @Test void 한_경매의_JSON_생성이_실패해도_다른_경매는_전송하고_실패를_기록한다() throws Exception {
        Long failing = f.openAuction(seller);
        Long healthy = f.openAuction(seller);
        BlockingQueue<Event> failingViewer = connect(younghee, failing);
        BlockingQueue<Event> healthyViewer = connect(younghee, healthy);
        next(failingViewer);
        next(healthyViewer);
        bid(chulsoo, failing, 50_000);
        bid(chulsoo, healthy, 50_000);
        double before = flushFailures();
        failSerialization(failing);

        streams.flush();

        assertThat(next(healthyViewer).data()).contains("\"auctionId\":" + healthy);
        assertThat(flushFailures()).isEqualTo(before + 1);
        assertThat(streams.connections(failing)).as("실패한 경매의 연결은 유지한다").isEqualTo(1);

        reset(json);
        bid(younghee, failing, 51_000);
        streams.flush();
        assertThat(next(failingViewer).data()).contains("\"amount\":51000");
    }

    @Test void 연결_직후_JSON_생성이_실패하면_연결을_남기지_않는다() throws Exception {
        Long auctionId = f.openAuction(seller);
        failSerialization(auctionId);

        // 구현이 없으면 스트림이 열린 채 끝나지 않으므로, 본문 전체를 기다리지 않는다.
        HttpResponse<Stream<String>> response = http.send(request(auctionId, younghee), HttpResponse.BodyHandlers.ofLines());
        opened.add(response.body());

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(streams.connections(auctionId)).isZero();
    }
}
