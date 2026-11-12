package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.CreditWalletService;
import com.restless.auction.application.CreditWalletService.WalletView;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class CreditWalletServiceTest {
    static final Timestamp T = Timestamp.from(Instant.parse("2026-11-20T01:00:00Z"));
    static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-20T02:00:00Z"));

    @Autowired CreditWalletService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    TransactionTemplate tx;
    Long sellerId;
    Long userA;
    Long userB;
    Long auctionId;
    long nextSequence;

    @BeforeEach void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM credit_transactions");
        jdbc.update("DELETE FROM credit_holds");
        jdbc.update("DELETE FROM credit_wallets");
        jdbc.update("DELETE FROM auction_results");
        jdbc.update("DELETE FROM auction_bids");
        jdbc.update("DELETE FROM auctions");
        jdbc.update("DELETE FROM users WHERE email LIKE 'credit-test-%'");
        sellerId = user("seller");
        userA = user("a");
        userB = user("b");
        jdbc.update("""
            INSERT INTO auctions (seller_user_id, title, item_name, item_description, starting_price_amount,
                minimum_increment_amount, access_policy, status, starts_at, base_ends_at, ends_at,
                extension_duration_seconds, created_at, updated_at)
            VALUES (?, '경매', '물품', '설명', 1000, 1000, 'ALL', 'OPEN', ?, ?, ?, 60, ?, ?)
            """, sellerId, T, LATER, LATER, T, T);
        auctionId = jdbc.queryForObject("SELECT MAX(auction_id) FROM auctions", Long.class);
        nextSequence = 1;
    }

    Long user(String name) {
        String email = "credit-test-" + name + "@example.com";
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES (?, 'hash', ?, 'USER', 'ACTIVE', ?, ?)
            """, email, "credit-" + name, T, T);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 확보가 참조할 입찰 행. 크레딧 테스트라 입찰 규칙은 따지지 않는다. */
    Long bid(Long userId) {
        long seq = nextSequence++;
        jdbc.update("""
            INSERT INTO auction_bids (auction_id, bidder_user_id, bid_amount, bid_sequence, request_key, accepted_at)
            VALUES (?, ?, 1000, ?, ?, ?)
            """, auctionId, userId, seq, "key-" + seq, T);
        return jdbc.queryForObject("SELECT auction_bid_id FROM auction_bids WHERE auction_id = ? AND bid_sequence = ?",
            Long.class, auctionId, seq);
    }

    <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    void inTx(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    /** INV-AU-14, 15: 지갑 금액 = HELD 합계 = 내역 합계. invariants.md의 확인 SQL. */
    void assertLedgerConsistent() {
        assertThat(jdbc.queryForList("""
            SELECT w.credit_wallet_id FROM credit_wallets w
            LEFT JOIN credit_holds h ON h.credit_wallet_id = w.credit_wallet_id AND h.status = 'HELD'
            GROUP BY w.credit_wallet_id, w.reserved_amount
            HAVING w.reserved_amount <> COALESCE(SUM(h.held_amount), 0)
            """)).isEmpty();
        assertThat(jdbc.queryForList("""
            SELECT w.credit_wallet_id FROM credit_wallets w
            LEFT JOIN credit_transactions t ON t.credit_wallet_id = w.credit_wallet_id
            GROUP BY w.credit_wallet_id, w.balance_amount, w.reserved_amount
            HAVING w.balance_amount <> COALESCE(SUM(t.balance_delta), 0)
                OR w.reserved_amount <> COALESCE(SUM(t.reserved_delta), 0)
            """)).isEmpty();
    }

    ErrorCode errorOf(Runnable work) {
        try {
            work.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    @Test void 지갑이_없으면_모두_0이다() {
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_wallets", Integer.class)).isZero();
    }

    @Test void 지급하면_지갑을_만들고_누를_때마다_10000씩_쌓인다() {
        assertThat(service.grantTestCredit(userA)).isEqualTo(new WalletView(10_000, 0, 10_000));
        assertThat(service.grantTestCredit(userA)).isEqualTo(new WalletView(20_000, 0, 20_000));
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(20_000, 0, 20_000));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM credit_transactions WHERE transaction_type = 'GRANT'", Integer.class)).isEqualTo(2);
        assertLedgerConsistent();
    }

    @Test void 확보_해제_차감이_내역과_함께_반영된다() {
        service.grantTestCredit(userA);
        Long bid1 = bid(userA);
        Long bid2 = bid(userA);

        inTx(() -> service.hold(userA, bid1, 6_000));
        inTx(() -> service.hold(userA, bid2, 3_000));
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(10_000, 9_000, 1_000));

        inTx(() -> service.release(bid1));
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(10_000, 3_000, 7_000));

        inTx(() -> service.capture(bid2));
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(7_000, 0, 7_000));

        assertThat(jdbc.queryForList(
            "SELECT transaction_type FROM credit_transactions ORDER BY credit_transaction_id", String.class))
            .containsExactly("GRANT", "HOLD", "HOLD", "RELEASE", "CAPTURE");
        assertLedgerConsistent();
    }

    @Test void 이미_해제나_차감된_확보를_다시_처리해도_한_번만_반영된다_INV_AU_12() {
        service.grantTestCredit(userA);
        Long bid1 = bid(userA);
        Long bid2 = bid(userA);
        inTx(() -> service.hold(userA, bid1, 1_000));
        inTx(() -> service.hold(userA, bid2, 1_000));

        inTx(() -> service.release(bid1));
        inTx(() -> service.release(bid1));
        inTx(() -> service.capture(bid2));
        inTx(() -> service.capture(bid2));

        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(9_000, 0, 9_000));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Integer.class)).isEqualTo(5);
        assertLedgerConsistent();
    }

    @Test void 크레딧이_부족하거나_지갑이_없으면_거절하고_아무것도_남기지_않는다() {
        Long noWalletBid = bid(userB);
        assertThat(errorOf(() -> inTx(() -> service.hold(userB, noWalletBid, 1_000))))
            .isEqualTo(ErrorCode.CREDIT_INSUFFICIENT);

        service.grantTestCredit(userA);
        Long bid1 = bid(userA);
        assertThat(errorOf(() -> inTx(() -> service.hold(userA, bid1, 10_001))))
            .isEqualTo(ErrorCode.CREDIT_INSUFFICIENT);

        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(10_000, 0, 10_000));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_holds", Integer.class)).isZero();
        assertLedgerConsistent();
    }

    @Test void 확보_해제_차감은_트랜잭션_밖에서_부를_수_없다() {
        assertThatThrownBy(() -> service.hold(userA, 1L, 1_000)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.release(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.capture(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.lockWallets(List.of(userA))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void 처음_지급을_동시에_눌러도_지갑은_하나고_전부_지급된다() throws Exception {
        int threads = 10;
        List<Future<ErrorCode>> results = runConcurrently(threads, i -> () -> service.grantTestCredit(userA));
        for (Future<ErrorCode> r : results) assertThat(r.get()).isNull();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_wallets WHERE user_id = ?", Integer.class, userA))
            .isEqualTo(1);
        assertThat(service.getWallet(userA).balanceAmount()).isEqualTo(threads * CreditWalletService.TEST_GRANT_AMOUNT);
        assertLedgerConsistent();
    }

    @Test void 동시에_여러_입찰을_묶어도_사용가능액을_넘지_않는다_INV_AU_06() throws Exception {
        service.grantTestCredit(userA);                 // 10,000
        int threads = 10;
        List<Long> bids = new ArrayList<>();
        for (int i = 0; i < threads; i++) bids.add(bid(userA));

        List<Future<ErrorCode>> results = runConcurrently(threads,
            i -> () -> inTx(() -> service.hold(userA, bids.get(i), 3_000)));

        int success = 0;
        for (Future<ErrorCode> r : results) {
            ErrorCode code = r.get();
            if (code == null) success++;
            else assertThat(code).isEqualTo(ErrorCode.CREDIT_INSUFFICIENT);
        }
        assertThat(success).isEqualTo(3);
        assertThat(service.getWallet(userA)).isEqualTo(new WalletView(10_000, 9_000, 1_000));
        assertLedgerConsistent();
    }

    @Test void 두_지갑을_반대_순서로_요청해도_ID_순으로_잠가_데드락이_없다() throws Exception {
        service.grantTestCredit(userA);
        service.grantTestCredit(userB);
        int threads = 20;
        List<Future<ErrorCode>> results = runConcurrently(threads, i -> () -> inTx(() -> {
            service.lockWallets(i % 2 == 0 ? List.of(userA, userB) : List.of(userB, userA));
            sleep(20);
        }));
        for (Future<ErrorCode> r : results) assertThat(r.get()).isNull();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    interface Task { Runnable at(int index); }

    /** 동시에 시작해 BusinessException 코드만 모은다. 다른 예외(데드락 등)는 Future.get()에서 터진다. */
    List<Future<ErrorCode>> runConcurrently(int threads, Task task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Runnable work = task.at(i);
            results.add(pool.submit(() -> {
                start.await();
                return errorOf(work);
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return results;
    }
}
