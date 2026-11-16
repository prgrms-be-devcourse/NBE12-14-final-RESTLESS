package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.BidService;
import com.restless.auction.application.BidService.BidResult;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 동시 입찰. 모든 요청을 한꺼번에 출발시키고, 끝난 뒤 불변식 SQL로 확인한다. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class BidConcurrencyTest {
    @Autowired BidService bidService;
    @Autowired JdbcTemplate jdbc;
    AuctionFixtures f;
    Long seller;

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
    }

    /** 결과: 성공이면 BidResult, 업무 거절이면 ErrorCode. 그 밖의 예외는 테스트 실패. */
    List<Object> runConcurrently(List<Callable<BidResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<BidResult> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) results.add(future.get());
        return results;
    }

    static long successes(List<Object> results) {
        return results.stream().filter(r -> r instanceof BidResult).count();
    }

    @Test void 같은_금액_동시_입찰은_한_건만_수락된다_INV_AU_01() throws Exception {
        Long auctionId = f.openAuction(seller);
        List<Callable<BidResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Long user = f.user("USER");
            f.credit(user, 100_000);
            tasks.add(() -> bidService.place(user, auctionId, 50_000, UUID.randomUUID().toString()));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(1);
        assertThat(results).filteredOn(r -> !(r instanceof BidResult)).containsOnly(ErrorCode.BID_AMOUNT_TOO_LOW);
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM credit_holds WHERE status = 'HELD'")).isEqualTo(1);
        assertThat(f.count("SELECT revision FROM auctions WHERE auction_id = ?", auctionId)).isEqualTo(1);
        f.assertInvariants();
    }

    @Test void 서로_다른_금액이_몰려도_최고_입찰과_묶인_확보는_하나다() throws Exception {
        Long auctionId = f.openAuction(seller);
        List<Callable<BidResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Long user = f.user("USER");
            f.credit(user, 200_000);
            long amount = 50_000 + (i % 10) * 3_000L;
            tasks.add(() -> bidService.place(user, auctionId, amount, UUID.randomUUID().toString()));
        }
        List<Object> results = runConcurrently(tasks);

        long accepted = successes(results);
        assertThat(accepted).isPositive();
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo((int) accepted);
        assertThat(f.count("SELECT COUNT(*) FROM credit_holds WHERE status = 'HELD'")).isEqualTo(1);
        assertThat(f.count("SELECT revision FROM auctions WHERE auction_id = ?", auctionId)).isEqualTo((int) accepted);
        assertThat(results).filteredOn(r -> !(r instanceof BidResult))
            .allMatch(r -> r == ErrorCode.BID_AMOUNT_TOO_LOW);
        f.assertInvariants();
    }

    @Test void 한_회원이_여러_경매에_동시_입찰해도_크레딧을_넘지_않는다_INV_AU_06() throws Exception {
        Long user = f.user("USER");
        f.credit(user, 120_000);
        List<Callable<BidResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long auctionId = f.openAuction(seller);
            tasks.add(() -> bidService.place(user, auctionId, 50_000, UUID.randomUUID().toString()));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(2);
        assertThat(results).filteredOn(r -> !(r instanceof BidResult)).containsOnly(ErrorCode.CREDIT_INSUFFICIENT);
        assertThat(f.available(user)).isEqualTo(20_000);
        f.assertInvariants();
    }

    @Test void 같은_키_동시_재전송은_입찰_한_건이고_모두_같은_입찰을_받는다_INV_AU_12() throws Exception {
        Long auctionId = f.openAuction(seller);
        Long user = f.user("USER");
        f.credit(user, 100_000);
        String key = UUID.randomUUID().toString();
        List<Callable<BidResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> bidService.place(user, auctionId, 50_000, key));
        List<Object> results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(10);
        Set<Long> bidIds = new HashSet<>();
        for (Object r : results) bidIds.add(((BidResult) r).bidId());
        assertThat(bidIds).hasSize(1);
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM credit_transactions WHERE transaction_type = 'HOLD'")).isEqualTo(1);
        assertThat(f.available(user)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test void 같은_키를_다른_경매에_동시에_보내면_하나만_수락된다() throws Exception {
        Long user = f.user("USER");
        f.credit(user, 500_000);
        String key = UUID.randomUUID().toString();
        List<Callable<BidResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long auctionId = f.openAuction(seller);
            tasks.add(() -> bidService.place(user, auctionId, 50_000, key));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(successes(results)).isEqualTo(1);
        assertThat(results).filteredOn(r -> !(r instanceof BidResult)).containsOnly(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(1);
        f.assertInvariants();
    }
}
