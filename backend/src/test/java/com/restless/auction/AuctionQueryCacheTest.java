package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.AuctionChangedEvent;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 경매 목록·상세 조회 캐시. DB 조회 횟수는 {@link AuctionQueryService} 호출 횟수로 센다.
 *
 * <p>상세는 경매가 바뀌면 커밋 직후 새 값을 보여 주고, 경매 이벤트가 없는 변경(닉네임)은 TTL 뒤에 반영한다.
 * 목록은 TTL 동안 재사용한다. TTL은 주입된 {@link Clock} 기준이라 실제로 기다리지 않는다.
 */
@SpringBootTest(properties = {
    "spring.docker.compose.enabled=false",
    "restless.auction.query-cache.enabled=true",
    "restless.auction.query-cache.ttl-ms=1000"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuctionQueryCacheTest.ClockConfig.class})
class AuctionQueryCacheTest {
    private static final Duration AFTER_TTL = Duration.ofMillis(1_001);

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired OffsetClock clock;
    @Autowired AuctionCloseService closeService;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuctionQueryService queries;

    AuctionFixtures f;
    Long seller;
    Long bidder;
    Long auctionId;

    @BeforeEach void setUp() {
        clock.advance(AFTER_TTL); // 앞 테스트의 목록 캐시가 남지 않게 한다.
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        bidder = f.user("USER");
        f.credit(bidder, 100_000);
        auctionId = f.openAuction(seller);
    }

    String bearer(Long userId, String role) {
        return "Bearer " + tokenProvider.issue(userId, role).value();
    }

    ResultActions detail(Long id) throws Exception {
        return mockMvc.perform(get("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(bidder, "USER")));
    }

    ResultActions list(String status, int page, int size) throws Exception {
        var request = get("/api/auctions").param("page", String.valueOf(page)).param("size", String.valueOf(size))
            .header(HttpHeaders.AUTHORIZATION, bearer(bidder, "USER"));
        return mockMvc.perform(status == null ? request : request.param("status", status));
    }

    ResultActions bid(long amount) throws Exception {
        return mockMvc.perform(post("/api/auctions/" + auctionId + "/bids")
            .header(HttpHeaders.AUTHORIZATION, bearer(bidder, "USER"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }

    Long scheduledAuction() {
        Instant startsAt = clock.instant().plusSeconds(3_600);
        Long id = f.auction(seller, startsAt, startsAt.plusSeconds(3_600));
        f.setStatus(id, "SCHEDULED");
        return id;
    }

    Instant serverTime(ResultActions result) throws Exception {
        return Instant.parse(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.serverTime"));
    }

    @Test void 같은_상세를_반복_조회하면_DB를_한_번만_읽는다() throws Exception {
        detail(auctionId).andExpect(status().isOk()).andExpect(jsonPath("$.auctionId").value(auctionId));
        detail(auctionId).andExpect(status().isOk()).andExpect(jsonPath("$.auctionId").value(auctionId));
        detail(auctionId).andExpect(status().isOk()).andExpect(jsonPath("$.auctionId").value(auctionId));

        verify(queries, times(1)).detail(auctionId);
    }

    @Test void 같은_상세를_동시에_조회해도_DB를_한_번만_읽는다() throws Exception {
        int requests = 8;
        CountDownLatch loaders = new CountDownLatch(requests);
        doAnswer(invocation -> {
            // 캐시가 동시 조회를 묶지 못하면 모든 요청이 여기 도착해 바로 지나가고, 호출 횟수 검증에서 실패한다.
            loaders.countDown();
            loaders.await(500, TimeUnit.MILLISECONDS);
            return invocation.callRealMethod();
        }).when(queries).detail(auctionId);

        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                responses.add(executor.submit(() -> {
                    start.await();
                    return detail(auctionId).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> response : responses) {
                assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        verify(queries, times(1)).detail(auctionId);
    }

    @Test void 입찰이_수락되면_다음_상세는_바로_새_최고가를_보여_준다() throws Exception {
        detail(auctionId).andExpect(jsonPath("$.highestBid").isEmpty());

        bid(50_000).andExpect(status().isCreated());

        detail(auctionId)
            .andExpect(jsonPath("$.highestBid.amount").value(50_000))
            .andExpect(jsonPath("$.minimumBidAmount").value(51_000));
        verify(queries, times(2)).detail(auctionId);
        f.assertInvariants();
    }

    @Test void 거절된_입찰은_상세_캐시를_버리지_않는다() throws Exception {
        detail(auctionId).andExpect(status().isOk());

        bid(49_999).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"));

        detail(auctionId).andExpect(status().isOk());
        verify(queries, times(1)).detail(auctionId);
    }

    @Test void 롤백된_변경은_상세_캐시를_버리지_않는다() throws Exception {
        detail(auctionId).andExpect(jsonPath("$.title").value("경매"));

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE auctions SET title = '롤백된 제목' WHERE auction_id = ?", auctionId);
            events.publishEvent(new AuctionChangedEvent(auctionId, 99));
            tx.setRollbackOnly();
        });

        detail(auctionId).andExpect(jsonPath("$.title").value("경매"));
        verify(queries, times(1)).detail(auctionId);
    }

    @Test void 경매가_시작되면_다음_상세는_바로_OPEN이다() throws Exception {
        f.setStatus(auctionId, "SCHEDULED");
        detail(auctionId).andExpect(jsonPath("$.status").value("SCHEDULED"));

        assertThat(closeService.open(auctionId)).isTrue();

        detail(auctionId).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test void 경매가_종료되면_다음_상세는_바로_CLOSED다() throws Exception {
        detail(auctionId).andExpect(jsonPath("$.status").value("OPEN"));
        f.setEndsAt(auctionId, clock.instant().minusSeconds(1));

        assertThat(closeService.close(auctionId)).isTrue();

        detail(auctionId).andExpect(jsonPath("$.status").value("CLOSED"));
        f.assertInvariants();
    }

    @Test void 시작_전_경매를_수정하면_다음_상세는_바로_새_값이다() throws Exception {
        Long id = scheduledAuction();
        detail(id).andExpect(jsonPath("$.title").value("경매"));
        Instant startsAt = clock.instant().plusSeconds(7_200);

        mockMvc.perform(patch("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller, "ORGANIZER"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":"바뀐 경매","itemName":"물품","itemDescription":"설명",
                     "startingPriceAmount":60000,"minimumIncrementAmount":1000,
                     "startsAt":"%s","endsAt":"%s","extensionDurationSeconds":60}
                    """.formatted(startsAt, startsAt.plusSeconds(3_600))))
            .andExpect(status().isOk());

        detail(id).andExpect(jsonPath("$.title").value("바뀐 경매"))
            .andExpect(jsonPath("$.minimumBidAmount").value(60_000));
    }

    @Test void 시작_전_경매를_삭제하면_다음_상세는_바로_404다() throws Exception {
        Long id = scheduledAuction();
        detail(id).andExpect(status().isOk());

        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(seller, "ORGANIZER")))
            .andExpect(status().isNoContent());

        detail(id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test void 캐시된_상세도_serverTime은_응답_시각이다() throws Exception {
        Instant first = serverTime(detail(auctionId).andExpect(status().isOk()));

        clock.advance(Duration.ofMillis(300));
        Instant second = serverTime(detail(auctionId).andExpect(status().isOk()));

        assertThat(Duration.between(first, second)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        verify(queries, times(1)).detail(auctionId);
    }

    @Test void 경매_이벤트가_없는_닉네임_변경은_TTL_뒤에_상세에_반영된다() throws Exception {
        String before = f.nickname(seller);
        detail(auctionId).andExpect(jsonPath("$.sellerNickname").value(before));
        jdbc.update("UPDATE users SET nickname = '새닉네임' WHERE id = ?", seller);

        detail(auctionId).andExpect(jsonPath("$.sellerNickname").value(before));

        clock.advance(AFTER_TTL);
        detail(auctionId).andExpect(jsonPath("$.sellerNickname").value("새닉네임"));
        verify(queries, times(2)).detail(auctionId);
    }

    @Test void 없는_경매의_404는_캐시하지_않는다() throws Exception {
        long missing = auctionId + 1_000;

        detail(missing).andExpect(status().isNotFound());
        detail(missing).andExpect(status().isNotFound());

        verify(queries, times(2)).detail(missing);
    }

    @Test void 같은_목록은_TTL_동안_재사용하고_TTL_뒤에_새로_읽는다() throws Exception {
        list("OPEN", 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        f.openAuction(seller);

        list("OPEN", 0, 20).andExpect(jsonPath("$.totalElements").value(1));
        verify(queries, times(1)).list(AuctionStatus.OPEN, 0, 20);

        clock.advance(AFTER_TTL);
        list("OPEN", 0, 20).andExpect(jsonPath("$.totalElements").value(2));
        verify(queries, times(2)).list(AuctionStatus.OPEN, 0, 20);
    }

    @Test void 상태_페이지_크기가_다른_목록은_섞이지_않는다() throws Exception {
        f.openAuction(seller);
        Long scheduled = scheduledAuction();

        list("OPEN", 0, 1).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.totalElements").value(2));
        list("OPEN", 1, 1).andExpect(jsonPath("$.items.length()").value(1));
        list("OPEN", 0, 20).andExpect(jsonPath("$.items.length()").value(2));
        list("SCHEDULED", 0, 20).andExpect(jsonPath("$.items[0].auctionId").value(scheduled));
        list(null, 0, 20).andExpect(jsonPath("$.totalElements").value(3));

        verify(queries, times(5)).list(any(), anyInt(), anyInt());
        verify(queries, times(1)).list(eq(AuctionStatus.OPEN), eq(0), eq(1));
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary OffsetClock auctionQueryCacheTestClock() {
            return new OffsetClock();
        }
    }

    /** 실제 시각에 더한 값만 앞으로 옮긴다. 고정 시각이 아니라서 발급한 JWT가 나중에 만료되지 않는다. */
    static class OffsetClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        void advance(Duration duration) {
            offset = offset.plus(duration);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return Instant.now().plus(offset);
        }
    }
}
