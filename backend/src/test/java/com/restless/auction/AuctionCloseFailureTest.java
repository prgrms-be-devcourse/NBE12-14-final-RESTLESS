package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.AuctionScheduler;
import com.restless.auction.application.BidService;
import com.restless.auction.domain.AuctionResult;
import com.restless.auction.infrastructure.AuctionResultRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 종료 처리 도중 실패를 일부러 넣어 확인한다 (operations/failure-handling.md A-05, A-06).
 * 실제 코드는 정상이고, 테스트에서만 특정 빈이 예외를 던지게 바꾼다.
 */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class AuctionCloseFailureTest {
    @MockitoSpyBean AuctionCloseService closeService;
    @Autowired BidService bidService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoSpyBean AuctionResultRepository results;
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        f.credit(chulsoo, 100_000);
        younghee = f.user("USER");
        f.credit(younghee, 100_000);
    }

    /** 같은 트랜잭션 안에서 본 크레딧 상태. 실패 직전에 차감이 실제로 반영돼 있었는지 확인하는 데 쓴다. */
    record CreditState(long balance, long reserved, String holdStatus, int captureCount) {
    }

    CreditState creditState() {
        return new CreditState(
                jdbc.queryForObject("SELECT balance_amount FROM credit_wallets WHERE user_id = ?", Long.class, chulsoo),
                jdbc.queryForObject("SELECT reserved_amount FROM credit_wallets WHERE user_id = ?", Long.class, chulsoo),
                jdbc.queryForObject("SELECT status FROM credit_holds", String.class),
                f.count("SELECT COUNT(*) FROM credit_transactions WHERE transaction_type = 'CAPTURE'"));
    }

    @Test void 차감_뒤_결과_저장에서_실패하면_차감까지_함께_롤백되고_다시_종료하면_한_번만_차감된다_INV_AU_13() {
        Long auctionId = f.openAuction(seller);
        bidService.place(chulsoo, auctionId, 60_000, UUID.randomUUID().toString());
        f.expireNow(auctionId);

        // 결과를 저장하려는 순간 실패시킨다. 그 직전 같은 트랜잭션 안의 크레딧 상태를 기록해 둔다.
        AtomicReference<CreditState> beforeFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            em.flush();
            beforeFailure.set(creditState());
            throw new IllegalStateException("테스트가 넣은 장애: 결과 저장 실패");
        }).when(results).save(any(AuctionResult.class));

        assertThatThrownBy(() -> closeService.close(auctionId))
                .hasMessageContaining("테스트가 넣은 장애");

        // 실패 직전에는 차감이 반영돼 있었다. 이 장애가 "돈만 빠지는" 지점에서 났다는 뜻이다.
        assertThat(beforeFailure.get()).isEqualTo(new CreditState(40_000, 0, "CAPTURED", 1));

        // 롤백 뒤: 차감, 결과, 종료 모두 없다. 입찰 직후 상태 그대로다.
        assertThat(creditState()).isEqualTo(new CreditState(100_000, 60_000, "HELD", 0));
        assertThat(f.count("SELECT COUNT(*) FROM auction_results WHERE auction_id = ?", auctionId)).isZero();
        assertThat(f.status(auctionId)).isEqualTo("OPEN");
        f.assertInvariants();

        // 장애를 풀고 다시 종료하면 (재시작 뒤 다음 주기) 정상 낙찰되고 차감은 한 번이다.
        reset(results);
        assertThat(closeService.close(auctionId)).isTrue();

        assertThat(creditState()).isEqualTo(new CreditState(40_000, 0, "CAPTURED", 1));
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?",
                String.class, auctionId)).isEqualTo("SOLD");
        assertThat(f.status(auctionId)).isEqualTo("CLOSED");
        f.assertInvariants();
    }

    /**
     * 스케줄러 빈은 테스트에서 꺼져 있다 (build.gradle). 켜면 백그라운드 스레드가 아무 때나 경매를 닫으므로,
     * 운영과 같은 클래스를 직접 만들어 테스트가 부를 때만 한 번 돌게 한다.
     */
    AuctionScheduler scheduler(SimpleMeterRegistry meters) {
        AuctionScheduler scheduler = new AuctionScheduler(closeService, meters);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
        return scheduler;
    }

    @Test void 스케줄러에서_경매_하나의_종료가_실패해도_나머지는_종료되고_실패한_경매는_다음_주기에_종료된다() {
        Long first = f.openAuction(seller);
        Long broken = f.openAuction(seller);
        Long last = f.openAuction(seller);
        bidService.place(chulsoo, first, 60_000, UUID.randomUUID().toString());
        bidService.place(younghee, broken, 70_000, UUID.randomUUID().toString());
        f.expireNow(first);
        f.expireNow(broken);
        f.expireNow(last);

        doThrow(new IllegalStateException("테스트가 넣은 장애: 종료 실패")).when(closeService).close(broken);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AuctionScheduler scheduler = scheduler(meters);

        scheduler.run();

        // 실패한 경매만 남고 앞뒤 경매는 종료됐다. 예외가 반복문 밖으로 나가지 않았다.
        assertThat(f.status(first)).isEqualTo("CLOSED");
        assertThat(f.status(last)).isEqualTo("CLOSED");
        assertThat(f.status(broken)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?",
                String.class, first)).isEqualTo("SOLD");
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?",
                String.class, last)).isEqualTo("UNSOLD");
        assertThat(f.count("SELECT COUNT(*) FROM auction_results WHERE auction_id = ?", broken)).isZero();
        assertThat(f.available(younghee)).as("실패한 경매의 확보는 그대로").isEqualTo(30_000);
        assertThat(meters.counter("auction.scheduler.failure", "action", "close").count()).isEqualTo(1.0);
        f.assertInvariants();

        // 장애를 풀면 다음 주기에 남은 경매도 종료된다.
        reset(closeService);
        scheduler.run();

        assertThat(f.status(broken)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?",
                String.class, broken)).isEqualTo("SOLD");
        assertThat(f.balance(younghee)).isEqualTo(30_000);
        assertThat(meters.counter("auction.scheduler.failure", "action", "close").count()).isEqualTo(1.0);
        f.assertInvariants();
    }
}
