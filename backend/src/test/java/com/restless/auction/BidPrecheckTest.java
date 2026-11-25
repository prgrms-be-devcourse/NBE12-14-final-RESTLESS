package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.BidService;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.auth.infrastructure.JwtTokenProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 입찰 잠금 전 금액 확인. {@link BidService#place}가 경매 행을 잠그는 경로이므로, 호출되지 않으면 잠금 줄을 서지 않은 것이다.
 *
 * <p>캐시 값이 오래돼도 판단이 틀리지 않는지 보려고 TTL을 길게 둔다. 경매가 바뀌면 세대 번호로 바로 새 값을 읽는다.
 */
@SpringBootTest(properties = {
    "spring.docker.compose.enabled=false",
    "restless.auction.query-cache.enabled=true",
    "restless.auction.query-cache.ttl-ms=60000"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuctionQueryCacheTest.ClockConfig.class})
class BidPrecheckTest {
    private static final String FAILURE_METRIC = "auction.bid.precheck.failure";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuctionQueryCacheTest.OffsetClock clock;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean BidService bidService;
    @MockitoSpyBean BidRepository bids;
    @MockitoSpyBean AuctionRepository auctions;

    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;
    Long auctionId;

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        f.credit(chulsoo, 100_000);
        f.credit(younghee, 100_000);
        auctionId = f.openAuction(seller);
    }

    String bearer(Long userId) {
        return "Bearer " + tokenProvider.issue(userId, userId.equals(seller) ? "ORGANIZER" : "USER").value();
    }

    ResultActions bid(Long userId, Long auction, long amount, String key) throws Exception {
        return mockMvc.perform(post("/api/auctions/" + auction + "/bids")
            .header(HttpHeaders.AUTHORIZATION, bearer(userId))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"));
    }

    ResultActions bid(Long userId, long amount) throws Exception {
        return bid(userId, auctionId, amount, UUID.randomUUID().toString());
    }

    void verifyLockedPathCalls(int count) {
        verify(bidService, times(count)).place(anyLong(), anyLong(), anyLong(), anyString());
    }

    double failureCount() {
        Counter counter = meters.find(FAILURE_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    double precheck(String result, String reason) {
        Counter counter = meters.find("auction.bid.precheck").tag("result", result).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    int bidCount() {
        return f.count("SELECT COUNT(*) FROM auction_bids WHERE auction_id = ?", auctionId);
    }

    @Test void 확실히_부족한_금액은_잠금_경로에_들어가지_않고_거절한다() throws Exception {
        bid(chulsoo, 52_000).andExpect(status().isCreated());

        bid(younghee, 52_999).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"))
            .andExpect(jsonPath("$.details.minimumBidAmount").value(53_000));

        verifyLockedPathCalls(1);
        assertThat(bidCount()).isEqualTo(1);
        assertThat(f.available(younghee)).isEqualTo(100_000);
        f.assertInvariants();
    }

    @Test void 입찰이_없으면_시작가보다_낮은_금액을_바로_거절한다() throws Exception {
        bid(chulsoo, 49_999).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"))
            .andExpect(jsonPath("$.details.minimumBidAmount").value(50_000));

        verify(bidService, never()).place(any(), any(), anyLong(), any());
    }

    @Test void 충분한_금액은_잠금_경로에서_수락된다() throws Exception {
        bid(chulsoo, 50_000).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("HIGHEST"));

        verifyLockedPathCalls(1);
        assertThat(f.available(chulsoo)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test void 요청_키는_금액_부족_후보일_때만_미리_조회한다() throws Exception {
        String acceptedKey = UUID.randomUUID().toString();
        bid(chulsoo, auctionId, 52_000, acceptedKey).andExpect(status().isCreated());
        verify(bids, times(1)).findByBidderUserIdAndRequestKey(chulsoo, acceptedKey);

        String rejectedKey = UUID.randomUUID().toString();
        bid(younghee, auctionId, 52_999, rejectedKey).andExpect(status().isConflict());
        verify(bids, times(1)).findByBidderUserIdAndRequestKey(younghee, rejectedKey);
        verifyLockedPathCalls(1);
    }

    @Test void 수락된_입찰의_재요청은_가격이_올라도_처음_결과를_돌려준다() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        Number bidId = JsonPath.read(first, "$.bidId");
        bid(younghee, 53_000).andExpect(status().isCreated());

        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated())
            .andExpect(jsonPath("$.bidId").value(bidId.longValue()))
            .andExpect(jsonPath("$.status").value("OUTBID"));

        assertThat(bidCount()).isEqualTo(2);
        f.assertInvariants();
    }

    @Test void 같은_키로_다른_낮은_금액을_보내면_키_충돌이다() throws Exception {
        String key = UUID.randomUUID().toString();
        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated());

        bid(chulsoo, auctionId, 51_000, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test void 같은_키를_다른_경매에_낮은_금액으로_보내면_키_충돌이다() throws Exception {
        String key = UUID.randomUUID().toString();
        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated());
        Long other = f.openAuction(seller);

        bid(chulsoo, other, 1_000, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test void 판매자의_낮은_금액은_판매자_거절을_유지한다() throws Exception {
        bid(seller, 1_000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BID_BY_SELLER"));
    }

    @Test void 방금_최고_입찰자가_된_회원의_낮은_금액은_최고_입찰자_거절이다() throws Exception {
        bid(chulsoo, 52_000).andExpect(status().isCreated());

        bid(chulsoo, 1_000).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_ALREADY_HIGHEST"));
    }

    @Test void 시작_전_경매의_낮은_금액은_시작_전_거절을_유지한다() throws Exception {
        Instant startsAt = clock.instant().plusSeconds(3_600);
        Long scheduled = f.auction(seller, startsAt, startsAt.plusSeconds(3_600));
        f.setStatus(scheduled, "SCHEDULED");

        bid(chulsoo, scheduled, 1_000, UUID.randomUUID().toString()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUCTION_NOT_OPEN"));
    }

    @Test void 캐시된_종료_시각이_지나면_낮은_금액도_마감_거절이다() throws Exception {
        Instant now = clock.instant();
        Long ending = f.auction(seller, now.minusSeconds(60), now.plusSeconds(5));
        bid(chulsoo, ending, 1_000, UUID.randomUUID().toString()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"));

        clock.advance(Duration.ofSeconds(6));

        bid(chulsoo, ending, 1_000, UUID.randomUUID().toString()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUCTION_CLOSED"));
    }

    @Test void 없는_경매는_404를_유지한다() throws Exception {
        bid(chulsoo, auctionId + 1_000, 1_000, UUID.randomUUID().toString()).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test void 경매_상태를_읽지_못하면_기존_경로로_처리하고_실패를_기록한다() throws Exception {
        double before = failureCount();
        doThrow(new IllegalStateException("cache load failed")).when(auctions).findById(auctionId);

        bid(chulsoo, 49_999).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"));

        verifyLockedPathCalls(1);
        assertThat(failureCount()).isEqualTo(before + 1);
    }

    @Test void 요청_키를_읽지_못하면_기존_경로로_처리하고_실패를_기록한다() throws Exception {
        double before = failureCount();
        String key = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("key lookup failed")).doReturn(Optional.empty())
            .when(bids).findByBidderUserIdAndRequestKey(chulsoo, key);

        bid(chulsoo, auctionId, 49_999, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"));

        verifyLockedPathCalls(1);
        assertThat(failureCount()).isEqualTo(before + 1);
    }

    @Test void 동시_입찰에서도_최고가와_불변식이_맞다() throws Exception {
        int bidders = 12;
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < bidders; i++) {
            Long user = f.user("USER");
            f.credit(user, 100_000);
            users.add(user);
        }
        ExecutorService executor = Executors.newFixedThreadPool(bidders);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < bidders; i++) {
                Long user = users.get(i);
                long amount = 50_000 + (i % 4) * 1_000L;
                futures.add(executor.submit(() -> {
                    start.await();
                    return bid(user, amount).andReturn().getResponse();
                }));
            }
            start.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        long accepted = 0;
        long highestAccepted = 0;
        for (MockHttpServletResponse response : responses) {
            assertThat(response.getStatus()).isIn(201, 409);
            if (response.getStatus() == 201) {
                accepted++;
                Number amount = JsonPath.read(response.getContentAsString(), "$.amount");
                highestAccepted = Math.max(highestAccepted, amount.longValue());
            } else {
                String code = JsonPath.read(response.getContentAsString(), "$.code");
                assertThat(code).isEqualTo("BID_AMOUNT_TOO_LOW");
            }
        }
        assertThat(accepted).isPositive();
        assertThat(bidCount()).isEqualTo((int) accepted);
        mockMvc.perform(get("/api/auctions/" + auctionId).header(HttpHeaders.AUTHORIZATION, bearer(chulsoo)))
            .andExpect(jsonPath("$.highestBid.amount").value(highestAccepted));
        f.assertInvariants();
    }
    @Test void 사전_확인_판단마다_결과와_이유를_센다() throws Exception {
        double amountOk = precheck("pass", "amount_ok");
        double tooLow = precheck("reject", "amount_too_low");
        double highest = precheck("pass", "highest_bidder");
        double seller = precheck("pass", "seller");
        double replay = precheck("pass", "replay");

        String key = UUID.randomUUID().toString();
        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated());
        bid(younghee, 52_999).andExpect(status().isConflict());
        bid(chulsoo, 1_000).andExpect(status().isConflict());
        bid(this.seller, 1_000).andExpect(status().isConflict());
        bid(younghee, 53_000).andExpect(status().isCreated());
        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated());

        assertThat(precheck("pass", "amount_ok")).isEqualTo(amountOk + 2);
        assertThat(precheck("reject", "amount_too_low")).isEqualTo(tooLow + 1);
        assertThat(precheck("pass", "highest_bidder")).isEqualTo(highest + 1);
        assertThat(precheck("pass", "seller")).isEqualTo(seller + 1);
        assertThat(precheck("pass", "replay")).as("가격이 올라 금액 부족 후보지만 같은 키의 재요청이다").isEqualTo(replay + 1);
    }

    @Test void 진행_중이_아니거나_상태가_없거나_오류면_통과로_세고_이유를_남긴다() throws Exception {
        double notOpen = precheck("pass", "not_open");
        double noState = precheck("pass", "no_state");
        double error = precheck("pass", "error");

        Instant startsAt = clock.instant().plusSeconds(3_600);
        Long scheduled = f.auction(seller, startsAt, startsAt.plusSeconds(3_600));
        f.setStatus(scheduled, "SCHEDULED");
        bid(chulsoo, scheduled, 1_000, UUID.randomUUID().toString()).andExpect(status().isConflict());
        bid(chulsoo, auctionId + 1_000, 1_000, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        doThrow(new IllegalStateException("cache load failed")).when(auctions).findById(auctionId);
        bid(chulsoo, 49_999).andExpect(status().isConflict());

        assertThat(precheck("pass", "not_open")).isEqualTo(notOpen + 1);
        assertThat(precheck("pass", "no_state")).isEqualTo(noState + 1);
        assertThat(precheck("pass", "error")).isEqualTo(error + 1);
    }
}
