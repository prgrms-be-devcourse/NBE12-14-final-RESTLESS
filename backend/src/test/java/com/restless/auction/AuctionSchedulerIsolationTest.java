package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.application.PaymentReconciler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 결제 대사가 PG 응답을 기다리며 멈춰 있어도 경매 종료는 제시간에 처리된다 (#315).
 *
 * <p>모든 {@code @Scheduled} 작업은 Spring의 공용 실행기를 같이 쓴다. 스레드가 1개면 대사가 끝날 때까지
 * 경매 시작·종료가 실행되지 못한다. 여기서는 PG를 부르는 대신 {@link PaymentReconciler#runOnce()}를
 * 직접 멈춰 "PG 응답 대기"를 흉내 낸다. 실제 PG는 부르지 않는다.
 * 스케줄러를 켜므로 다른 테스트와 별도 컨텍스트(별도 DB 컨테이너)에서 돈다.
 */
@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "restless.auction.scheduler.enabled=true",
        "restless.auction.scheduler.fixed-delay-ms=200",
        "restless.payment.reconcile.enabled=true",
        "restless.payment.reconcile.fixed-delay-ms=200"})
@Import(TestcontainersConfiguration.class)
class AuctionSchedulerIsolationTest {
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PaymentReconciler reconciler;

    final CountDownLatch reconcileStarted = new CountDownLatch(1);
    final CountDownLatch releaseReconcile = new CountDownLatch(1);

    @AfterEach void release() {
        releaseReconcile.countDown();
    }

    @Test void 결제_대사가_멈춰_있어도_경매는_제시간에_종료된다() throws Exception {
        AuctionFixtures f = new AuctionFixtures(jdbc);
        f.clean();
        Long seller = f.user("ORGANIZER");
        Long auctionId = f.openAuction(seller);

        // 대사를 멈춘다. 풀려난 뒤에는 실제 대사를 하지 않고 바로 끝난다.
        doAnswer(invocation -> {
            reconcileStarted.countDown();
            releaseReconcile.await(60, TimeUnit.SECONDS);
            return null;
        }).when(reconciler).runOnce();
        assertThat(reconcileStarted.await(10, TimeUnit.SECONDS)).as("대사가 멈춘 상태로 들어갔다").isTrue();

        f.expireNow(auctionId);

        AuctionSchedulerTest.waitFor(() -> "CLOSED".equals(f.status(auctionId)), 5_000);
        assertThat(releaseReconcile.getCount()).as("대사는 아직 멈춰 있다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_type FROM auction_results WHERE auction_id = ?", String.class, auctionId))
            .isEqualTo("UNSOLD");
        f.assertInvariants();
    }
}
