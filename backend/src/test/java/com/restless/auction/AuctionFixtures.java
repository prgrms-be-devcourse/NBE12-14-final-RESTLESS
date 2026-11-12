package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 경매 통합 테스트용 데이터와 불변식 확인 SQL. 시각은 UTC 벽시계 값으로 넣는다 (엔티티가 TIMESTAMP_UTC). */
class AuctionFixtures {
    final JdbcTemplate jdbc;

    AuctionFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    void clean() {
        jdbc.update("DELETE FROM credit_transactions");
        jdbc.update("DELETE FROM credit_holds");
        jdbc.update("DELETE FROM credit_wallets");
        jdbc.update("DELETE FROM auction_results");
        jdbc.update("DELETE FROM auction_bids");
        jdbc.update("DELETE FROM auctions");
    }

    Long user(String role) {
        String email = "bid-test-" + UUID.randomUUID() + "@example.com";
        LocalDateTime now = utc(Instant.now());
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES (?, 'hash', ?, ?, 'ACTIVE', ?, ?)
            """, email, "닉" + email.substring(9, 17), role, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    String nickname(Long userId) {
        return jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, userId);
    }

    /** 지갑과 GRANT 내역을 함께 넣어 장부가 맞게 한다. */
    void credit(Long userId, long amount) {
        LocalDateTime now = utc(Instant.now());
        jdbc.update("""
            INSERT INTO credit_wallets (user_id, balance_amount, reserved_amount, created_at, updated_at)
            VALUES (?, ?, 0, ?, ?)
            """, userId, amount, now, now);
        Long walletId = jdbc.queryForObject("SELECT credit_wallet_id FROM credit_wallets WHERE user_id = ?", Long.class, userId);
        jdbc.update("""
            INSERT INTO credit_transactions (credit_wallet_id, transaction_type, balance_delta, reserved_delta,
                balance_after, reserved_after, operation_key, created_at)
            VALUES (?, 'GRANT', ?, 0, ?, 0, ?, ?)
            """, walletId, amount, amount, "GRANT:" + UUID.randomUUID(), now);
    }

    /** 시작가 50,000, 최소 인상액 1,000, 연장 60초. */
    Long auction(Long sellerId, Instant startsAt, Instant endsAt) {
        LocalDateTime now = utc(Instant.now());
        jdbc.update("""
            INSERT INTO auctions (seller_user_id, title, item_name, item_description, starting_price_amount,
                minimum_increment_amount, access_policy, status, starts_at, base_ends_at, ends_at,
                extension_duration_seconds, created_at, updated_at)
            VALUES (?, '경매', '물품', '설명', 50000, 1000, 'ALL', 'OPEN', ?, ?, ?, 60, ?, ?)
            """, sellerId, utc(startsAt), utc(endsAt), utc(endsAt), now, now);
        return jdbc.queryForObject("SELECT MAX(auction_id) FROM auctions", Long.class);
    }

    /** 1분 전에 시작해 1시간 뒤 끝나는 진행 중 경매. */
    Long openAuction(Long sellerId) {
        return auction(sellerId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    long available(Long userId) {
        Long v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(balance_amount - reserved_amount), 0) FROM credit_wallets WHERE user_id = ?
            """, Long.class, userId);
        return v == null ? 0 : v;
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** invariants.md 확인 SQL: INV-AU-07, 08, 14, 15. */
    void assertInvariants() {
        assertThat(jdbc.queryForList("""
            SELECT a.auction_id FROM auctions a
            JOIN auction_bids b ON b.auction_id = a.auction_id
             AND b.bid_sequence = (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = a.auction_id)
            LEFT JOIN credit_holds h ON h.auction_bid_id = b.auction_bid_id AND h.status = 'HELD'
            WHERE a.status <> 'CLOSED' AND h.credit_hold_id IS NULL
            """)).as("INV-AU-07 최고 입찰은 확보돼 있다").isEmpty();
        assertThat(jdbc.queryForList("""
            SELECT h.credit_hold_id FROM credit_holds h
            JOIN auction_bids b ON b.auction_bid_id = h.auction_bid_id
            WHERE h.status = 'HELD'
              AND b.bid_sequence < (SELECT MAX(bid_sequence) FROM auction_bids WHERE auction_id = b.auction_id)
            """)).as("INV-AU-08 추월된 입찰의 확보는 해제돼 있다").isEmpty();
        assertThat(jdbc.queryForList("""
            SELECT w.credit_wallet_id FROM credit_wallets w
            LEFT JOIN credit_holds h ON h.credit_wallet_id = w.credit_wallet_id AND h.status = 'HELD'
            GROUP BY w.credit_wallet_id, w.reserved_amount
            HAVING w.reserved_amount <> COALESCE(SUM(h.held_amount), 0)
            """)).as("INV-AU-14").isEmpty();
        assertThat(jdbc.queryForList("""
            SELECT w.credit_wallet_id FROM credit_wallets w
            LEFT JOIN credit_transactions t ON t.credit_wallet_id = w.credit_wallet_id
            GROUP BY w.credit_wallet_id, w.balance_amount, w.reserved_amount
            HAVING w.balance_amount <> COALESCE(SUM(t.balance_delta), 0)
                OR w.reserved_amount <> COALESCE(SUM(t.reserved_delta), 0)
            """)).as("INV-AU-15").isEmpty();
        assertThat(jdbc.queryForList("""
            SELECT b2.auction_bid_id FROM auction_bids b1
            JOIN auction_bids b2 ON b2.auction_id = b1.auction_id AND b2.bid_sequence = b1.bid_sequence + 1
            JOIN auctions a ON a.auction_id = b1.auction_id
            WHERE b2.bid_amount < b1.bid_amount + a.minimum_increment_amount
            """)).as("INV-AU-03 다음 입찰은 이전 + 최소 인상액 이상").isEmpty();
    }
}
