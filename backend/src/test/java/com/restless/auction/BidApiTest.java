package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BidApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
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
        f.credit(younghee, 80_000);
        auctionId = f.openAuction(seller);
    }

    String bearer(Long userId) {
        return "Bearer " + tokenProvider.issue(userId, "USER").value();
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

    @Test void 첫_입찰은_시작가부터_받고_크레딧이_묶인다() throws Exception {
        bid(chulsoo, 49_999).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"))
            .andExpect(jsonPath("$.details.minimumBidAmount").value(50_000));
        bid(chulsoo, 50_000).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("HIGHEST"))
            .andExpect(jsonPath("$.amount").value(50_000))
            .andExpect(jsonPath("$.bidId").isNumber())
            .andExpect(jsonPath("$.endsAt").isString());
        assertThat(f.available(chulsoo)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test void 추월하면_이전_입찰자_크레딧이_풀리고_상세에_새_최고_입찰이_보인다() throws Exception {
        bid(chulsoo, 52_000).andExpect(status().isCreated());
        bid(younghee, 52_999).andExpect(status().isConflict())
            .andExpect(jsonPath("$.details.minimumBidAmount").value(53_000));
        bid(younghee, 55_000).andExpect(status().isCreated());

        assertThat(f.available(chulsoo)).isEqualTo(100_000);
        assertThat(f.available(younghee)).isEqualTo(25_000);
        mockMvc.perform(get("/api/auctions/" + auctionId).header(HttpHeaders.AUTHORIZATION, bearer(chulsoo)))
            .andExpect(jsonPath("$.highestBid.nickname").value(f.nickname(younghee)))
            .andExpect(jsonPath("$.highestBid.amount").value(55_000))
            .andExpect(jsonPath("$.minimumBidAmount").value(56_000))
            .andExpect(jsonPath("$.revision").value(2));
        assertThat(f.count("SELECT COUNT(*) FROM credit_holds WHERE status = 'RELEASED'")).isEqualTo(1);
        f.assertInvariants();
    }

    @Test void 판매자와_현재_최고_입찰자는_입찰할_수_없다() throws Exception {
        f.credit(seller, 100_000);
        bid(seller, 50_000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BID_BY_SELLER"));
        bid(chulsoo, 50_000).andExpect(status().isCreated());
        bid(chulsoo, 60_000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BID_ALREADY_HIGHEST"));
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(1);
    }

    @Test void 크레딧이_부족하거나_지갑이_없으면_거절되고_아무것도_남지_않는다() throws Exception {
        Long poor = f.user("USER");
        bid(poor, 50_000).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREDIT_INSUFFICIENT"));
        bid(younghee, 80_001).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREDIT_INSUFFICIENT"));
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isZero();
        assertThat(f.count("SELECT COUNT(*) FROM credit_holds")).isZero();
        assertThat(f.count("SELECT revision FROM auctions WHERE auction_id = ?", auctionId)).isZero();
        f.assertInvariants();
    }

    @Test void 시작_전이나_종료_후에는_입찰할_수_없다() throws Exception {
        Long future = f.auction(seller, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now().plus(2, ChronoUnit.HOURS));
        bid(chulsoo, future, 50_000, UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_NOT_OPEN"));
        Long ended = f.auction(seller, Instant.now().minus(2, ChronoUnit.HOURS), Instant.now().minusSeconds(1));
        bid(chulsoo, ended, 50_000, UUID.randomUUID().toString())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUCTION_CLOSED"));
        bid(chulsoo, 999_999L, 50_000, UUID.randomUUID().toString())
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test void 종료_직전_입찰은_종료_시각을_연장한다() throws Exception {
        Instant endsSoon = Instant.now().plusSeconds(20).truncatedTo(ChronoUnit.MILLIS);
        Long closing = f.auction(seller, Instant.now().minusSeconds(60), endsSoon);
        Instant before = Instant.now();
        String res = bid(chulsoo, closing, 50_000, UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Instant endsAt = Instant.parse(com.jayway.jsonpath.JsonPath.read(res, "$.endsAt"));
        assertThat(endsAt).isAfter(endsSoon).isAfterOrEqualTo(before.plusSeconds(60).minusMillis(5));
        assertThat(f.count("SELECT COUNT(*) FROM auctions WHERE auction_id = ? AND base_ends_at < ends_at", closing))
            .isEqualTo(1);
    }

    @Test void 같은_키로_다시_보내면_한_번만_반영되고_현재_상태를_준다() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        Number bidId = com.jayway.jsonpath.JsonPath.read(first, "$.bidId");

        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated())
            .andExpect(jsonPath("$.bidId").value(bidId.longValue()))
            .andExpect(jsonPath("$.status").value("HIGHEST"));
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(1);
        assertThat(f.available(chulsoo)).isEqualTo(48_000);

        bid(younghee, 55_000).andExpect(status().isCreated());
        bid(chulsoo, auctionId, 52_000, key).andExpect(status().isCreated())
            .andExpect(jsonPath("$.bidId").value(bidId.longValue()))
            .andExpect(jsonPath("$.status").value("OUTBID"));

        bid(chulsoo, auctionId, 60_000, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        Long other = f.openAuction(seller);
        bid(chulsoo, other, 52_000, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(f.count("SELECT COUNT(*) FROM auction_bids")).isEqualTo(2);
        f.assertInvariants();
    }

    @Test void 거절된_키로_다시_보내면_새로_판단한다() throws Exception {
        Long poor = f.user("USER");
        String key = UUID.randomUUID().toString();
        bid(poor, auctionId, 50_000, key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CREDIT_INSUFFICIENT"));
        f.credit(poor, 50_000);
        bid(poor, auctionId, 50_000, key).andExpect(status().isCreated());
        f.assertInvariants();
    }

    @Test void 요청_형식이_잘못되면_400() throws Exception {
        mockMvc.perform(post("/api/auctions/" + auctionId + "/bids").header(HttpHeaders.AUTHORIZATION, bearer(chulsoo))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
        bid(chulsoo, auctionId, 0, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        bid(chulsoo, auctionId, 50_000, "x".repeat(101)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auctions/" + auctionId + "/bids").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000}"))
            .andExpect(status().isUnauthorized());
    }
}
