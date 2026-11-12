package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.BidService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 스케줄러를 켠 채로 경매 한 건이 시작부터 낙찰까지 자동으로 진행되는지 확인한다.
 * 속성이 달라 다른 테스트와 별도 컨텍스트(별도 DB 컨테이너)에서 돈다.
 */
@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "restless.auction.scheduler.enabled=true",
        "restless.auction.scheduler.fixed-delay-ms=200"})
@Import(TestcontainersConfiguration.class)
class AuctionSchedulerTest {
    @Autowired BidService bidService;
    @Autowired JdbcTemplate jdbc;

    @Test void 시작_시각에_열리고_종료_시각에_낙찰된다() throws Exception {
        AuctionFixtures f = new AuctionFixtures(jdbc);
        f.clean();
        Long seller = f.user("ORGANIZER");
        Long buyer = f.user("USER");
        f.credit(buyer, 100_000);
        Long auctionId = f.auction(seller, Instant.now().plusSeconds(1), Instant.now().plusSeconds(60));
        f.setStatus(auctionId, "SCHEDULED");

        waitFor(() -> "OPEN".equals(f.status(auctionId)), 10_000);
        bidService.place(buyer, auctionId, 50_000, UUID.randomUUID().toString());
        f.setEndsAt(auctionId, Instant.now().plusSeconds(1));

        waitFor(() -> "CLOSED".equals(f.status(auctionId)), 10_000);
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?", String.class, auctionId))
            .isEqualTo("SOLD");
        assertThat(f.balance(buyer)).isEqualTo(50_000);
        f.assertInvariants();
    }

    static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met in " + timeoutMillis + "ms");
            Thread.sleep(100);
        }
    }
}
