package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.SellerAuctionService;
import com.restless.auction.domain.Auction;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 수정·삭제는 경매 행을 잠근 뒤의 시각으로 "시작 전"을 판단해야 한다 (transactions.md TX-AU-5).
 * 잠금을 기다리는 사이 시작 시각이 지나면, 기다리기 전에 읽은 시각으로는 이미 시작된 경매를 바꾸게 된다.
 *
 * <p>다른 연결이 경매 행을 잠근 채로, 수정·삭제가 실제로 잠금 대기에 들어간 것을 확인한 뒤
 * 시계를 시작 시각 뒤로 옮기고 잠금을 푼다. 실제 시간이 흐르기를 기다리지 않는다.
 */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import({TestcontainersConfiguration.class, SellerAuctionLockOrderTest.ClockConfig.class})
class SellerAuctionLockOrderTest {
    @Autowired SellerAuctionService sellerAuctionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired MySQLContainer mysql;
    AuctionFixtures f;
    Long seller;
    Instant startsAt;
    Long auctionId;
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        clock.now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        startsAt = clock.now.plusSeconds(60);
        auctionId = f.auction(seller, startsAt, startsAt.plusSeconds(3600));
        f.setStatus(auctionId, "SCHEDULED");
    }

    @AfterEach void tearDown() {
        executor.shutdownNow();
    }

    @Test void 잠금을_기다리는_사이_시작_시각이_지나면_수정은_409_AUCTION_ALREADY_STARTED() throws Exception {
        Instant newStartsAt = startsAt.plusSeconds(3600);
        Auction.Terms terms = new Auction.Terms("바뀐 경매", "물품", "설명", 70_000, 1_000,
                newStartsAt, newStartsAt.plusSeconds(3600), 60);

        Throwable failure = runWhileStartPassesDuringLockWait(
                () -> sellerAuctionService.update(seller, auctionId, terms));

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getErrorCode()).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED);
        assertThat(jdbc.queryForObject("SELECT title FROM auctions WHERE auction_id = ?", String.class, auctionId))
                .as("시작된 경매는 바뀌지 않는다").isEqualTo("경매");
    }

    @Test void 잠금을_기다리는_사이_시작_시각이_지나면_삭제는_409_AUCTION_ALREADY_STARTED() throws Exception {
        Throwable failure = runWhileStartPassesDuringLockWait(
                () -> { sellerAuctionService.delete(seller, auctionId); return null; });

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getErrorCode()).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED);
        assertThat(f.count("SELECT COUNT(*) FROM auctions WHERE auction_id = ?", auctionId))
                .as("시작된 경매는 지워지지 않는다").isEqualTo(1);
    }

    /**
     * 다른 연결이 경매 행을 잠근 상태에서 작업을 시작하고, 작업이 잠금 대기에 들어가면
     * 시계를 시작 시각 뒤로 옮긴 다음 잠금을 푼다. 작업이 던진 예외를 돌려준다 (성공하면 null).
     */
    Throwable runWhileStartPassesDuringLockWait(java.util.concurrent.Callable<Object> task) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT auction_id FROM auctions WHERE auction_id = ? FOR UPDATE")) {
                lock.setLong(1, auctionId);
                lock.executeQuery().close();
            }

            Future<Object> result = executor.submit(task);
            await().atMost(Duration.ofSeconds(10)).until(this::someoneWaitsForAuctionLock);

            clock.now = startsAt.plusSeconds(1);
            holder.commit();

            try {
                result.get(10, TimeUnit.SECONDS);
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        }
    }

    /**
     * 경매 행 잠금을 기다리는 트랜잭션이 있는지. performance_schema는 앱 계정(test)에 권한이 없어
     * 컨테이너 root로 관찰만 한다 (PaymentFlowTest와 같은 방식). 앱 SQL은 그대로 일반 데이터소스를 쓴다.
     */
    boolean someoneWaitsForAuctionLock() throws Exception {
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = root.createStatement();
             var rows = statement.executeQuery("""
                     SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                     JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                     WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'auctions'
                     """)) {
            rows.next();
            return rows.getInt(1) > 0;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary MutableClock auctionTestClock() {
            return new MutableClock();
        }
    }

    /** 테스트가 옮길 수 있는 시계. */
    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }
}
