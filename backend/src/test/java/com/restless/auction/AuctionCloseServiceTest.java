package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.BidService;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 시작·종료 서비스를 직접 부른다 (스케줄러는 꺼져 있음). */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class AuctionCloseServiceTest {
    @Autowired AuctionCloseService closeService;
    @Autowired BidService bidService;
    @Autowired JdbcTemplate jdbc;
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        f.credit(chulsoo, 100_000);
        f.credit(younghee, 80_000);
    }

    void bid(Long user, Long auctionId, long amount) {
        bidService.place(user, auctionId, amount, UUID.randomUUID().toString());
    }

    Map<String, Object> result(Long auctionId) {
        return jdbc.queryForMap("SELECT result_type, winning_bid_id FROM auction_results WHERE auction_id = ?", auctionId);
    }

    @Test void 시작_시각이_지난_예정_경매만_연다() {
        Long due = f.auction(seller, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        Long future = f.auction(seller, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        f.setStatus(due, "SCHEDULED");
        f.setStatus(future, "SCHEDULED");

        assertThat(closeService.findDueToOpen(100)).containsExactly(due);
        assertThat(closeService.open(due)).isTrue();
        assertThat(closeService.open(due)).isFalse();
        assertThat(closeService.open(future)).isFalse();
        assertThat(f.status(due)).isEqualTo("OPEN");
        assertThat(f.status(future)).isEqualTo("SCHEDULED");
        assertThat(f.count("SELECT revision FROM auctions WHERE auction_id = ?", due)).isEqualTo(1);
    }

    @Test void 입찰이_있으면_최고_입찰자가_낙찰받고_크레딧이_차감된다() {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 52_000);
        bid(younghee, auctionId, 61_000);
        f.expireNow(auctionId);

        assertThat(closeService.findDueToClose(100)).containsExactly(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();

        assertThat(f.status(auctionId)).isEqualTo("CLOSED");
        Map<String, Object> r = result(auctionId);
        assertThat(r.get("result_type")).isEqualTo("SOLD");
        Long winning = jdbc.queryForObject(
            "SELECT auction_bid_id FROM auction_bids WHERE auction_id = ? AND bidder_user_id = ?", Long.class, auctionId, younghee);
        assertThat(((Number) r.get("winning_bid_id")).longValue()).isEqualTo(winning);
        assertThat(f.balance(younghee)).isEqualTo(19_000);
        assertThat(f.available(younghee)).isEqualTo(19_000);
        assertThat(f.balance(chulsoo)).isEqualTo(100_000);
        assertThat(f.available(chulsoo)).isEqualTo(100_000);
        assertThat(f.count("SELECT COUNT(*) FROM credit_holds WHERE status = 'CAPTURED'")).isEqualTo(1);
        f.assertInvariants();
    }

    @Test void 입찰이_없으면_유찰이다() {
        Long auctionId = f.openAuction(seller);
        f.expireNow(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();
        assertThat(result(auctionId).get("result_type")).isEqualTo("UNSOLD");
        assertThat(result(auctionId).get("winning_bid_id")).isNull();
        f.assertInvariants();
    }

    @Test void 시작_처리를_놓친_예정_경매도_종료된다() {
        Long auctionId = f.auction(seller, Instant.now().minusSeconds(120), Instant.now().minusSeconds(1));
        f.setStatus(auctionId, "SCHEDULED");
        assertThat(closeService.findDueToOpen(100)).contains(auctionId);
        assertThat(closeService.open(auctionId)).isFalse();
        assertThat(closeService.findDueToClose(100)).contains(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();
        assertThat(f.status(auctionId)).isEqualTo("CLOSED");
        f.assertInvariants();
    }

    @Test void 종료_시각_전이거나_이미_종료됐으면_아무것도_하지_않는다() {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 50_000);
        assertThat(closeService.close(auctionId)).isFalse();
        assertThat(f.status(auctionId)).isEqualTo("OPEN");

        f.expireNow(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();
        assertThat(closeService.close(auctionId)).isFalse();
        assertThat(f.count("SELECT COUNT(*) FROM credit_transactions WHERE transaction_type = 'CAPTURE'")).isEqualTo(1);
        assertThat(f.balance(chulsoo)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test void 종료된_경매에는_입찰할_수_없다() {
        Long auctionId = f.openAuction(seller);
        f.expireNow(auctionId);
        closeService.close(auctionId);
        f.setEndsAt(auctionId, Instant.now().plusSeconds(3600));     // 시각상 열려 있어도 상태가 CLOSED면 거절
        try {
            bid(chulsoo, auctionId, 50_000);
            throw new AssertionError("bid should be rejected");
        } catch (BusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUCTION_CLOSED);
        }
    }

    @Test void 종료를_동시에_여러_번_실행해도_결과와_차감은_한_번이다_INV_AU_02() throws Exception {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 50_000);
        f.expireNow(auctionId);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return closeService.close(auctionId);
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        int closed = 0;
        for (Future<Boolean> r : results) if (r.get()) closed++;

        assertThat(closed).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM auction_results")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM credit_transactions WHERE transaction_type = 'CAPTURE'")).isEqualTo(1);
        assertThat(f.balance(chulsoo)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test void 종료_직전_입찰과_종료가_경합해도_종료_시각_이후_입찰은_없고_낙찰자는_마지막_입찰자다() throws Exception {
        List<Long> bidders = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long u = f.user("USER");
            f.credit(u, 1_000_000);
            bidders.add(u);
        }
        // 연장 시간 30초(최소)라 입찰이 오면 종료가 미뤄진다. 종료를 앞당겨 경합을 만든다.
        Long auctionId = f.auction(seller, Instant.now().minusSeconds(60), Instant.now().plusMillis(300));
        jdbc.update("UPDATE auctions SET extension_duration_seconds = 30 WHERE auction_id = ?", auctionId);

        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(bidders.size() + 1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < bidders.size(); i++) {
            Long user = bidders.get(i);
            long base = 50_000 + i * 1_000L;
            futures.add(pool.submit(() -> {
                long amount = base;
                while (!stop.get()) {
                    try {
                        bidService.place(user, auctionId, amount, UUID.randomUUID().toString());
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.AUCTION_CLOSED) return;
                    }
                    amount += 10_000;
                }
            }));
        }
        futures.add(pool.submit(() -> {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                if (closeService.close(auctionId)) break;
                // 경합 중 종료되게 계속 앞당긴다. 잠금 뒤 재확인이 옛 종료 시각으로 닫는 것을 막는지 본다.
                f.expireNow(auctionId);
            }
            stop.set(true);
            return null;
        }));
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (Future<?> fu : futures) fu.get();

        assertThat(f.status(auctionId)).isEqualTo("CLOSED");
        assertThat(f.count("SELECT COUNT(*) FROM auction_results WHERE auction_id = ?", auctionId)).isEqualTo(1);
        f.assertInvariants();
    }
}
