package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.api.AuctionStreams;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.BidService;
import com.restless.auction.application.SellerAuctionService;
import com.restless.auction.domain.Auction;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 포트로 SSE를 연결해 확인한다. 자동 전송 간격을 1시간으로 늘리고
 * {@link AuctionStreams#flush()}를 직접 불러 "모아서 한 번" 보내는지 결정적으로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.docker.compose.enabled=false",
        "restless.auction.stream.flush-interval-ms=3600000",
})
@Import(TestcontainersConfiguration.class)
class AuctionStreamTest {
    @LocalServerPort int port;
    @Autowired AuctionStreams streams;
    @Autowired BidService bidService;
    @Autowired AuctionCloseService closeService;
    @Autowired SellerAuctionService sellerAuctionService;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;
    final HttpClient http = HttpClient.newHttpClient();
    final List<Stream<String>> opened = new ArrayList<>();

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        f.credit(chulsoo, 100_000);
        f.credit(younghee, 80_000);
    }

    @AfterEach void closeStreams() {
        opened.forEach(Stream::close);
    }

    /** SSE 메시지 하나. END는 서버가 연결을 닫았다는 표시다. */
    record Event(String name, String id, JsonNode data) {
        static final Event END = new Event("END", null, null);
    }

    /** 스트림을 열고 별도 스레드가 메시지를 큐에 넣는다. */
    BlockingQueue<Event> connect(Long userId, Long auctionId) throws Exception {
        HttpResponse<Stream<String>> response = http.send(request(auctionId, userId), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        opened.add(response.body());
        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            String name = null;
            String id = null;
            try {
                for (String line : (Iterable<String>) response.body()::iterator) {
                    if (line.startsWith("event:")) name = line.substring(6);
                    else if (line.startsWith("id:")) id = line.substring(3);
                    else if (line.startsWith("data:")) queue.add(new Event(name, id, json.readTree(line.substring(5))));
                }
            } catch (RuntimeException ignored) {
                // 테스트가 연결을 닫으면 읽기가 끊긴다.
            }
            queue.add(Event.END);
        });
        return queue;
    }

    HttpRequest request(Long auctionId, Long userId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auctions/" + auctionId + "/stream"))
                .header("Accept", "text/event-stream");
        if (userId != null) {
            builder.header("Authorization", "Bearer " + tokenProvider.issue(userId, "USER").value());
        }
        return builder.GET().build();
    }

    static Event next(BlockingQueue<Event> queue) throws InterruptedException {
        Event event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event).as("5초 안에 메시지가 와야 한다").isNotNull();
        return event;
    }

    void bid(Long userId, Long auctionId, long amount) {
        bidService.place(userId, auctionId, amount, UUID.randomUUID().toString());
    }

    long revision(Long auctionId) {
        return jdbc.queryForObject("SELECT revision FROM auctions WHERE auction_id = ?", Long.class, auctionId);
    }

    @Test void 토큰_없이_연결하면_401() throws Exception {
        Long auctionId = f.openAuction(seller);
        HttpResponse<String> response = http.send(request(auctionId, null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test void 없는_경매는_404() throws Exception {
        HttpResponse<String> response = http.send(request(999_999L, chulsoo), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test void 연결하면_현재_상태를_먼저_받는다() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<Event> queue = connect(chulsoo, auctionId);

        Event first = next(queue);
        assertThat(first.name()).isEqualTo("auction");
        assertThat(first.id()).isEqualTo(String.valueOf(revision(auctionId)));
        assertThat(first.data().get("auctionId").asLong()).isEqualTo(auctionId);
        assertThat(first.data().get("status").asString()).isEqualTo("OPEN");
        assertThat(first.data().get("highestBid").isNull()).isTrue();
        assertThat(first.data().get("minimumBidAmount").asLong()).isEqualTo(50_000);
        assertThat(first.data().get("result").isNull()).isTrue();
        assertThat(streams.connections(auctionId)).isEqualTo(1);
    }

    @Test void 여러_변경은_모아서_최신_상태_하나만_보낸다() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<Event> chulsooView = connect(chulsoo, auctionId);
        BlockingQueue<Event> youngheeView = connect(younghee, auctionId);
        next(chulsooView);
        next(youngheeView);

        bid(chulsoo, auctionId, 50_000);
        bid(younghee, auctionId, 51_000);
        bid(chulsoo, auctionId, 52_000);
        streams.flush();

        for (BlockingQueue<Event> queue : List.of(chulsooView, youngheeView)) {
            Event event = next(queue);
            assertThat(event.id()).isEqualTo(String.valueOf(revision(auctionId)));
            assertThat(event.data().get("highestBid").get("amount").asLong()).isEqualTo(52_000);
            assertThat(event.data().get("highestBid").get("nickname").asString()).isEqualTo(f.nickname(chulsoo));
            assertThat(event.data().get("minimumBidAmount").asLong()).isEqualTo(53_000);
            assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).as("세 번 바뀌어도 한 번만 보낸다").isNull();
        }

        streams.flush();
        assertThat(chulsooView.poll(300, TimeUnit.MILLISECONDS)).as("바뀐 것이 없으면 보내지 않는다").isNull();
    }

    @Test void 종료되면_낙찰_결과를_보내고_연결을_닫는다() throws Exception {
        Long auctionId = f.openAuction(seller);
        bid(younghee, auctionId, 60_000);
        BlockingQueue<Event> queue = connect(chulsoo, auctionId);
        next(queue);

        f.expireNow(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();
        streams.flush();

        Event closed = next(queue);
        assertThat(closed.data().get("status").asString()).isEqualTo("CLOSED");
        JsonNode result = closed.data().get("result");
        assertThat(result.get("type").asString()).isEqualTo("SOLD");
        assertThat(result.get("winnerNickname").asString()).isEqualTo(f.nickname(younghee));
        assertThat(result.get("amount").asLong()).isEqualTo(60_000);
        assertThat(next(queue)).as("종료 뒤 서버가 연결을 닫는다").isEqualTo(Event.END);
    }

    @Test void 이미_유찰된_경매에_연결하면_결과를_한_번_받고_닫힌다() throws Exception {
        Long auctionId = f.openAuction(seller);
        f.expireNow(auctionId);
        closeService.close(auctionId);

        BlockingQueue<Event> queue = connect(chulsoo, auctionId);
        Event event = next(queue);
        assertThat(event.data().get("status").asString()).isEqualTo("CLOSED");
        assertThat(event.data().get("result").get("type").asString()).isEqualTo("UNSOLD");
        assertThat(event.data().get("result").has("winnerNickname")).as("유찰이면 type만 보낸다").isFalse();
        assertThat(next(queue)).isEqualTo(Event.END);
    }

    @Test void 시작_전_경매를_수정하면_바뀐_상태를_받고_삭제하면_연결이_닫힌다() throws Exception {
        Long auctionId = f.auction(seller, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        f.setStatus(auctionId, "SCHEDULED");
        BlockingQueue<Event> queue = connect(chulsoo, auctionId);
        assertThat(next(queue).data().get("status").asString()).isEqualTo("SCHEDULED");

        Instant startsAt = Instant.now().plusSeconds(3600);
        sellerAuctionService.update(seller, auctionId, new Auction.Terms("경매", "물품", "설명", 70_000, 1_000,
                startsAt, startsAt.plusSeconds(3600), 60));
        streams.flush();
        Event updated = next(queue);
        assertThat(updated.id()).isEqualTo(String.valueOf(revision(auctionId)));
        assertThat(updated.data().get("minimumBidAmount").asLong()).isEqualTo(70_000);

        sellerAuctionService.delete(seller, auctionId);
        streams.flush();
        assertThat(next(queue)).isEqualTo(Event.END);
        assertThat(streams.connections(auctionId)).isZero();
    }

    @Test void 연결이_끊겨도_입찰과_확보는_그대로다_INV_AU_09() throws Exception {
        Long auctionId = f.openAuction(seller);
        BlockingQueue<Event> queue = connect(chulsoo, auctionId);
        next(queue);
        bid(chulsoo, auctionId, 50_000);

        opened.forEach(Stream::close);
        opened.clear();

        // 끊긴 연결로 보내다 실패해도 예외가 밖으로 나오지 않고, 끊긴 연결은 정리된다.
        long amount = 51_000;
        for (int i = 0; i < 20 && streams.connections(auctionId) > 0; i++) {
            bid(i % 2 == 0 ? younghee : chulsoo, auctionId, amount);
            amount += 1_000;
            streams.flush();
            Thread.sleep(100);
        }
        assertThat(streams.connections(auctionId)).isZero();

        assertThat(f.count("SELECT COUNT(*) FROM credit_holds WHERE status = 'HELD'")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids WHERE auction_id = ?", auctionId)).isGreaterThan(1);
        assertThat(f.status(auctionId)).isEqualTo("OPEN");
        f.assertInvariants();
    }
}
