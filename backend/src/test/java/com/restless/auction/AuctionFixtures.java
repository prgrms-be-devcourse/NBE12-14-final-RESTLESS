package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 경매 통합 테스트용 데이터와 불변식 확인 (SQL은 harness/auction-invariants.sql). 시각은 UTC 벽시계 값으로 넣는다 (엔티티가 TIMESTAMP_UTC). */
public class AuctionFixtures {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;

    public AuctionFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public void clean() {
        jdbc.update("DELETE FROM credit_charges");
        jdbc.update("DELETE FROM credit_transactions");
        jdbc.update("DELETE FROM credit_holds");
        jdbc.update("DELETE FROM credit_wallets");
        jdbc.update("DELETE FROM auction_results");
        jdbc.update("DELETE FROM auction_bids");
        jdbc.update("DELETE FROM auctions");
    }

    public Long user(String role) {
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
    public void credit(Long userId, long amount) {
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
    public Long openAuction(Long sellerId) {
        return auction(sellerId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    public long available(Long userId) {
        Long v = jdbc.queryForObject("""
            SELECT COALESCE(MAX(balance_amount - reserved_amount), 0) FROM credit_wallets WHERE user_id = ?
            """, Long.class, userId);
        return v == null ? 0 : v;
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /**
     * 불변식 SQL 파일({@code harness/auction-invariants.sql})의 쿼리를 모두 실행해, 어긴 행이 없는지 확인한다.
     * 같은 파일을 부하 테스트 뒤 점검에도 쓴다.
     */
    public void assertInvariants() {
        INVARIANTS.forEach((name, sql) -> assertThat(jdbc.queryForList(sql)).as(name).isEmpty());
    }

    private static final Map<String, String> INVARIANTS = loadInvariants();

    /** "-- name: <이름>" 블록마다 쿼리 하나를 읽는다. 블록 밖의 줄과 주석 줄은 건너뛴다. */
    private static Map<String, String> loadInvariants() {
        String sql;
        try {
            sql = new ClassPathResource("harness/auction-invariants.sql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> queries = new LinkedHashMap<>();
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.startsWith("-- name:")) {
                if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
                name = line.substring("-- name:".length()).trim();
                body.setLength(0);
            } else if (name != null && !line.startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
        if (queries.isEmpty()) throw new IllegalStateException("auction-invariants.sql에 쿼리가 없다");
        return queries;
    }

    /** 시간이 흐른 것처럼 종료 시각을 옮긴다. 앞당기면 base_ends_at도 함께 당겨 CHECK(base <= ends)를 지킨다. */
    void setEndsAt(Long auctionId, Instant endsAt) {
        jdbc.update("UPDATE auctions SET base_ends_at = LEAST(base_ends_at, ?), ends_at = ? WHERE auction_id = ?",
            utc(endsAt), utc(endsAt), auctionId);
    }

    /**
     * 시간이 흘러 종료 시각에 도달한 것처럼 종료 시각을 "지금"으로 당긴다.
     *
     * <p>먼저 경매 행을 잠가 진행 중인 입찰이 끝나기를 기다린 뒤, 다음 문장에서 시각을 계산한다.
     * MySQL은 UTC_TIMESTAMP()를 문장 시작 때 계산하므로, 한 문장으로 하면 잠금을 기다리는 동안
     * 이미 수락된 입찰보다 앞선 시각으로 당겨 버린다. 실제 서비스는 종료 시각을 당기지 않는다 (INV-AU-10).
     * 마지막 입찰 수락 시각보다 앞서지 않게 GREATEST로 한 번 더 막는다.
     */
    void expireNow(Long auctionId) {
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT auction_id FROM auctions WHERE auction_id = ? FOR UPDATE", Long.class, auctionId);
            jdbc.update("""
                UPDATE auctions
                SET ends_at = GREATEST(UTC_TIMESTAMP(6), COALESCE(
                        (SELECT MAX(accepted_at) + INTERVAL 1 MICROSECOND FROM auction_bids WHERE auction_id = ?),
                        UTC_TIMESTAMP(6))),
                    base_ends_at = LEAST(base_ends_at, ends_at)
                WHERE auction_id = ?
                """, auctionId, auctionId);
        });
    }

    void setStatus(Long auctionId, String status) {
        jdbc.update("UPDATE auctions SET status = ? WHERE auction_id = ?", status, auctionId);
    }

    String status(Long auctionId) {
        return jdbc.queryForObject("SELECT status FROM auctions WHERE auction_id = ?", String.class, auctionId);
    }

    public long balance(Long userId) {
        return jdbc.queryForObject("SELECT balance_amount FROM credit_wallets WHERE user_id = ?", Long.class, userId);
    }
}
