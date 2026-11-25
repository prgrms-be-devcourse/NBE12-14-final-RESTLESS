package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.api.AuctionStreams;
import com.restless.auction.application.BidService;
import com.restless.auth.infrastructure.JwtTokenProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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

/**
 * SSE 연결 상한(#244)과 heartbeat(#447). 상한을 2로 줄이고, 자동 전송과 heartbeat 간격을 1시간으로 늘려
 * {@link AuctionStreams#flush()}, {@link AuctionStreams#heartbeat()}를 직접 부른다.
 *
 * <p>클라이언트가 연결을 닫아도 서버는 그 연결에 무언가를 써 봐야 알 수 있다. 그래서 정리는 heartbeat를 불러 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.docker.compose.enabled=false",
        "restless.auction.stream.flush-interval-ms=3600000",
        "restless.auction.stream.heartbeat-interval-ms=3600000",
        "restless.auction.stream.max-connections=2",
        "restless.auction.stream.retry-after-seconds=5",
})
@Import(TestcontainersConfiguration.class)
class AuctionStreamLimitTest {
    @LocalServerPort int port;
    @Autowired AuctionStreams streams;
    @Autowired BidService bidService;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;
    final HttpClient http = HttpClient.newHttpClient();
    final List<Stream<String>> opened = new ArrayList<>();

    /** 받은 줄 그대로. END는 서버가 연결을 닫았다는 표시다. */
    static final String END = "<END>";

    @BeforeEach void setUp() throws Exception {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        f.credit(chulsoo, 100_000);
        f.credit(younghee, 100_000);
    }

    /** 다음 테스트가 빈 상한에서 시작하도록 클라이언트를 닫고 서버 쪽 연결이 정리될 때까지 heartbeat를 보낸다. */
    @AfterEach void closeStreams() throws Exception {
        closeClients();
        drainServerConnections();
    }

    void closeClients() {
        opened.forEach(Stream::close);
        opened.clear();
    }

    void drainServerConnections() throws InterruptedException {
        for (int i = 0; i < 50 && streams.totalConnections() > 0; i++) {
            streams.heartbeat();
            Thread.sleep(100);
        }
        assertThat(streams.totalConnections()).as("닫힌 클라이언트의 연결은 heartbeat로 정리된다").isZero();
    }

    HttpRequest request(Long auctionId, Long userId) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auctions/" + auctionId + "/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + tokenProvider.issue(userId, "USER").value())
                .GET().build();
    }

    /** 스트림을 열고 별도 스레드가 받은 줄을 큐에 넣는다. 빈 줄은 넣지 않는다. */
    BlockingQueue<String> connect(Long userId, Long auctionId) throws Exception {
        HttpResponse<Stream<String>> response = http.send(request(auctionId, userId), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        opened.add(response.body());
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try {
                for (String line : (Iterable<String>) response.body()::iterator) {
                    if (!line.isEmpty()) lines.add(line);
                }
            } catch (RuntimeException ignored) {
                // 테스트가 연결을 닫으면 읽기가 끊긴다.
            }
            lines.add(END);
        });
        return lines;
    }

    /** 다음 data 줄까지 읽는다. 사이의 event, id, 주석 줄은 건너뛴다. */
    static String nextData(BlockingQueue<String> lines) throws InterruptedException {
        while (true) {
            String line = lines.poll(5, TimeUnit.SECONDS);
            assertThat(line).as("5초 안에 data가 와야 한다").isNotNull().isNotEqualTo(END);
            if (line.startsWith("data:")) return line;
        }
    }

    double counter(String name) {
        Counter counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    double gauge() {
        Gauge gauge = meters.find("auction.stream.connections").gauge();
        return gauge == null ? -1 : gauge.value();
    }

    @Test void 상한에_닿으면_새_연결은_503과_Retry_After를_받고_기존_연결은_그대로_받는다() throws Exception {
        Long hot = f.openAuction(seller);
        Long other = f.openAuction(seller);
        BlockingQueue<String> first = connect(chulsoo, hot);
        BlockingQueue<String> second = connect(younghee, other);
        nextData(first);
        nextData(second);
        double rejectedBefore = counter("auction.stream.rejected");

        // 상한은 경매별이 아니라 서버 전체 연결 수다.
        HttpResponse<String> rejected = http.send(request(hot, younghee), HttpResponse.BodyHandlers.ofString());

        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(rejected.body()).contains("AUCTION_STREAM_BUSY");
        assertThat(counter("auction.stream.rejected")).isEqualTo(rejectedBefore + 1);
        assertThat(streams.totalConnections()).isEqualTo(2);
        assertThat(gauge()).isEqualTo(2);

        bidService.place(younghee, hot, 50_000, UUID.randomUUID().toString());
        streams.flush();
        assertThat(nextData(first)).as("거절이 있어도 기존 연결은 새 가격을 받는다").contains("\"amount\":50000");
        f.assertInvariants();
    }

    @Test void 끊긴_연결이_정리되면_자리가_다시_난다() throws Exception {
        Long auctionId = f.openAuction(seller);
        nextData(connect(chulsoo, auctionId));
        nextData(connect(younghee, auctionId));
        assertThat(http.send(request(auctionId, chulsoo), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);

        closeClients();
        drainServerConnections();

        nextData(connect(chulsoo, auctionId));
        assertThat(streams.totalConnections()).isEqualTo(1);
    }

    @Test void 없는_경매로_연결하면_404이고_자리를_차지하지_않는다() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(http.send(request(999_999L, chulsoo), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
        assertThat(streams.totalConnections()).isZero();

        Long auctionId = f.openAuction(seller);
        nextData(connect(chulsoo, auctionId));
        nextData(connect(younghee, auctionId));
        assertThat(streams.totalConnections()).isEqualTo(2);
    }

    @Test void heartbeat는_살아_있는_연결에_주석을_보내고_연결을_유지한다() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<String> lines = connect(chulsoo, auctionId);
        nextData(lines);

        streams.heartbeat();

        String line = lines.poll(5, TimeUnit.SECONDS);
        assertThat(line).as("주석 줄은 ':'로 시작해 클라이언트의 메시지 처리에 들어가지 않는다").startsWith(":");
        assertThat(streams.connections(auctionId)).isEqualTo(1);
        assertThat(lines.poll(300, TimeUnit.MILLISECONDS)).as("heartbeat는 상태 메시지를 보내지 않는다").isNull();
    }

    @Test void 입찰이_없어도_떠난_연결은_heartbeat로_정리된다() throws Exception {
        Long quiet = f.openAuction(seller);
        Long watched = f.openAuction(seller);
        nextData(connect(chulsoo, quiet));
        double removedBefore = counter("auction.stream.heartbeat.removed");
        // 다른 클라이언트의 연결은 남아 있어야 한다.
        HttpClient otherClient = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> alive = otherClient.send(request(watched, younghee), HttpResponse.BodyHandlers.ofLines());
        assertThat(alive.statusCode()).isEqualTo(200);

        closeClients();
        for (int i = 0; i < 50 && streams.connections(quiet) > 0; i++) {
            streams.heartbeat();
            Thread.sleep(100);
        }

        assertThat(streams.connections(quiet)).isZero();
        assertThat(streams.connections(watched)).isEqualTo(1);
        assertThat(counter("auction.stream.heartbeat.removed")).isEqualTo(removedBefore + 1);
        assertThat(gauge()).isEqualTo(1);
        opened.add(alive.body());
    }
}
