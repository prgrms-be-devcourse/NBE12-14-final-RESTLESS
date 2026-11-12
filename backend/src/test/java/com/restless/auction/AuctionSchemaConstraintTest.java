package com.restless.auction;

import com.restless.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V20261112_1045 경매·크레딧 보강 제약이 DB에서 실제로 막히는지 확인한다. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
class AuctionSchemaConstraintTest {
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    static final Timestamp T = Timestamp.from(NOW);

    @Autowired JdbcTemplate jdbc;
    Long sellerId;
    Long bidderId;

    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM credit_transactions");
        jdbc.update("DELETE FROM credit_holds");
        jdbc.update("DELETE FROM credit_wallets");
        jdbc.update("DELETE FROM auction_results");
        jdbc.update("DELETE FROM auction_bids");
        jdbc.update("DELETE FROM auctions");
        jdbc.update("DELETE FROM users WHERE email LIKE 'auction-schema-%'");
        sellerId = user("auction-schema-seller@example.com", "ORGANIZER");
        bidderId = user("auction-schema-bidder@example.com", "USER");
    }

    Long user(String email, String role) {
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES (?, 'test-hash', ?, ?, 'ACTIVE', ?, ?)
            """, email, email.substring(0, email.indexOf('@')), role, T, T);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    Long auction(String status, int extensionSeconds) {
        jdbc.update("""
            INSERT INTO auctions (seller_user_id, title, item_name, item_description, starting_price_amount,
                minimum_increment_amount, access_policy, status, starts_at, base_ends_at, ends_at,
                extension_duration_seconds, created_at, updated_at)
            VALUES (?, '경매', '물품', '설명', 10000, 1000, 'ALL', ?, ?, ?, ?, ?, ?, ?)
            """, sellerId, status, T, Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW.plusSeconds(3600)),
            extensionSeconds, T, T);
        return jdbc.queryForObject("SELECT MAX(auction_id) FROM auctions", Long.class);
    }

    Long bid(Long auctionId, Long userId, long sequence, String requestKey) {
        jdbc.update("""
            INSERT INTO auction_bids (auction_id, bidder_user_id, bid_amount, bid_sequence, request_key, accepted_at)
            VALUES (?, ?, 10000, ?, ?, ?)
            """, auctionId, userId, sequence, requestKey, T);
        return jdbc.queryForObject("SELECT MAX(auction_bid_id) FROM auction_bids", Long.class);
    }

    Long wallet(long balance, long reserved) {
        jdbc.update("""
            INSERT INTO credit_wallets (user_id, balance_amount, reserved_amount, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """, bidderId, balance, reserved, T, T);
        return jdbc.queryForObject("SELECT credit_wallet_id FROM credit_wallets WHERE user_id = ?", Long.class, bidderId);
    }

    void hold(Long walletId, Long bidId, String status, Timestamp releasedAt, Timestamp capturedAt) {
        jdbc.update("""
            INSERT INTO credit_holds (credit_wallet_id, auction_bid_id, held_amount, status, held_at, released_at, captured_at)
            VALUES (?, ?, 10000, ?, ?, ?, ?)
            """, walletId, bidId, status, T, releasedAt, capturedAt);
    }

    @Test void 연장_시간은_30초에서_300초만_허용한다() {
        assertThat(auction("SCHEDULED", 30)).isNotNull();
        assertThat(auction("SCHEDULED", 300)).isNotNull();
        assertThatThrownBy(() -> auction("SCHEDULED", 29))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_auctions_extension_range");
        assertThatThrownBy(() -> auction("SCHEDULED", 301))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_auctions_extension_range");
    }

    @Test void 경매_상태는_정해진_값만_허용한다() {
        assertThatThrownBy(() -> auction("CANCELLED", 60))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_auctions_status");
    }

    @Test void 요청_키는_회원별로_유일하다_INV_AU_12() {
        Long auctionId = auction("OPEN", 60);
        Long otherId = user("auction-schema-other@example.com", "USER");
        bid(auctionId, bidderId, 1, "same-key");
        assertThat(bid(auctionId, otherId, 2, "same-key")).isNotNull();
        Long otherAuctionId = auction("OPEN", 60);
        assertThatThrownBy(() -> bid(otherAuctionId, bidderId, 1, "same-key"))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_auction_bids_bidder_request_key");
    }

    @Test void 결과는_WON이면_입찰이_있고_NO_BID면_없다_INV_AU_02() {
        Long noBid = auction("CLOSED", 60);
        jdbc.update("INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at) VALUES (?, 'NO_BID', NULL, ?)", noBid, T);

        Long won = auction("CLOSED", 60);
        Long bidId = bid(won, bidderId, 1, "won-key");
        jdbc.update("INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at) VALUES (?, 'WON', ?, ?)", won, bidId, T);

        Long broken = auction("CLOSED", 60);
        Long brokenBid = bid(broken, bidderId, 1, "broken-key");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at) VALUES (?, 'WON', NULL, ?)", broken, T))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_auction_results_result_type");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at) VALUES (?, 'NO_BID', ?, ?)", broken, brokenBid, T))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_auction_results_result_type");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at) VALUES (?, 'NO_BID', NULL, ?)", noBid, T))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void 묶인_금액은_보유액을_넘을_수_없다_INV_AU_06() {
        Long walletId = wallet(10000, 10000);
        assertThatThrownBy(() -> jdbc.update("UPDATE credit_wallets SET reserved_amount = 10001 WHERE credit_wallet_id = ?", walletId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_wallets_reserved_within_balance");
    }

    @Test void 확보_상태와_시각은_짝이_맞아야_한다() {
        Long walletId = wallet(100000, 0);
        Long auctionId = auction("OPEN", 60);
        hold(walletId, bid(auctionId, bidderId, 1, "k1"), "HELD", null, null);
        hold(walletId, bid(auctionId, bidderId, 2, "k2"), "RELEASED", T, null);
        hold(walletId, bid(auctionId, bidderId, 3, "k3"), "CAPTURED", null, T);

        Long b4 = bid(auctionId, bidderId, 4, "k4");
        assertThatThrownBy(() -> hold(walletId, b4, "HELD", T, null))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_holds_status");
        assertThatThrownBy(() -> hold(walletId, b4, "RELEASED", null, null))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_holds_status");
        assertThatThrownBy(() -> hold(walletId, b4, "EXPIRED", null, null))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_holds_status");
    }

    @Test void 크레딧_내역_종류는_정해진_값만_허용한다() {
        Long walletId = wallet(10000, 0);
        jdbc.update("""
            INSERT INTO credit_transactions (credit_wallet_id, transaction_type, balance_delta, reserved_delta,
                balance_after, reserved_after, operation_key, created_at)
            VALUES (?, 'GRANT', 10000, 0, 10000, 0, 'GRANT:1', ?)
            """, walletId, T);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO credit_transactions (credit_wallet_id, transaction_type, balance_delta, reserved_delta,
                balance_after, reserved_after, operation_key, created_at)
            VALUES (?, 'REFUND', 0, 0, 10000, 0, 'REFUND:1', ?)
            """, walletId, T))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_credit_transactions_type");
    }
}
