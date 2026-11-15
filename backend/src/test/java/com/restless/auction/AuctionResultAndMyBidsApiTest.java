package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.auction.application.AuctionCloseService;
import com.restless.auction.application.BidService;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 결과 조회(GET /api/auctions/{id}/result)와 내 입찰 조회(GET /api/me/bids). */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuctionResultAndMyBidsApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired BidService bidService;
    @Autowired AuctionCloseService closeService;
    AuctionFixtures f;
    Long seller;
    Long chulsoo;
    Long younghee;

    @BeforeEach void setUp() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        seller = f.user("ORGANIZER");
        chulsoo = f.user("USER");
        younghee = f.user("USER");
        f.credit(chulsoo, 1_000_000);
        f.credit(younghee, 1_000_000);
    }

    String bearer(Long userId) {
        return "Bearer " + tokenProvider.issue(userId, "USER").value();
    }

    void bid(Long userId, Long auctionId, long amount) {
        bidService.place(userId, auctionId, amount, UUID.randomUUID().toString());
    }

    void close(Long auctionId) {
        f.expireNow(auctionId);
        assertThat(closeService.close(auctionId)).isTrue();
    }

    ResultActions result(Long auctionId) throws Exception {
        return mockMvc.perform(get("/api/auctions/" + auctionId + "/result")
            .header(HttpHeaders.AUTHORIZATION, bearer(chulsoo)));
    }

    ResultActions myBids(Long userId, String query) throws Exception {
        return mockMvc.perform(get("/api/me/bids" + query).header(HttpHeaders.AUTHORIZATION, bearer(userId)));
    }

    // ---- 결과 조회 ----

    @Test void 낙찰되면_낙찰자_닉네임과_금액을_준다() throws Exception {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 50_000);
        bid(younghee, auctionId, 51_000);
        close(auctionId);

        result(auctionId).andExpect(status().isOk())
            .andExpect(jsonPath("$.auctionId").value(auctionId))
            .andExpect(jsonPath("$.type").value("SOLD"))
            .andExpect(jsonPath("$.winnerNickname").value(f.nickname(younghee)))
            .andExpect(jsonPath("$.amount").value(51_000))
            .andExpect(jsonPath("$.finalizedAt").exists());
    }

    @Test void 유찰이면_type만_있고_낙찰자와_금액은_없다() throws Exception {
        Long auctionId = f.openAuction(seller);
        close(auctionId);

        result(auctionId).andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("UNSOLD"))
            .andExpect(jsonPath("$.finalizedAt").exists())
            .andExpect(jsonPath("$.winnerNickname").doesNotExist())
            .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test void 종료_전이면_404_AUCTION_RESULT_NOT_FOUND() throws Exception {
        Long auctionId = f.openAuction(seller);
        bid(chulsoo, auctionId, 50_000);

        result(auctionId).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUCTION_RESULT_NOT_FOUND"));
    }

    @Test void 없는_경매는_404_AUCTION_NOT_FOUND() throws Exception {
        result(999_999L).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    // ---- 내 입찰 조회 ----

    @Test void 경매마다_내_마지막_입찰과_상태를_최근_순으로_준다() throws Exception {
        Long outbid = f.openAuction(seller);      // 철수 → 영희가 추월
        Long highest = f.openAuction(seller);     // 철수 50,000 → 영희 51,000 → 철수 52,000 (최고)
        Long won = f.openAuction(seller);         // 철수 낙찰
        Long lost = f.openAuction(seller);        // 영희가 낙찰, 철수는 낙찰 실패
        bid(chulsoo, outbid, 50_000);
        bid(younghee, outbid, 51_000);
        bid(chulsoo, highest, 50_000);
        bid(younghee, highest, 51_000);
        bid(chulsoo, highest, 52_000);
        bid(chulsoo, won, 60_000);
        bid(chulsoo, lost, 50_000);
        bid(younghee, lost, 70_000);
        close(won);
        close(lost);

        myBids(chulsoo, "").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(4))
            .andExpect(jsonPath("$.items.length()").value(4))
            // 철수의 마지막 입찰 시각 순: lost(50,000) → won → highest(52,000) → outbid
            .andExpect(jsonPath("$.items[0].auctionId").value(lost))
            .andExpect(jsonPath("$.items[0].status").value("OUTBID"))
            .andExpect(jsonPath("$.items[0].auctionStatus").value("CLOSED"))
            .andExpect(jsonPath("$.items[1].auctionId").value(won))
            .andExpect(jsonPath("$.items[1].status").value("WON"))
            .andExpect(jsonPath("$.items[1].amount").value(60_000))
            .andExpect(jsonPath("$.items[2].auctionId").value(highest))
            .andExpect(jsonPath("$.items[2].status").value("HIGHEST"))
            .andExpect(jsonPath("$.items[2].amount").value(52_000))
            .andExpect(jsonPath("$.items[2].auctionStatus").value("OPEN"))
            .andExpect(jsonPath("$.items[2].title").value("경매"))
            .andExpect(jsonPath("$.items[2].itemName").value("물품"))
            .andExpect(jsonPath("$.items[2].bidId").exists())
            .andExpect(jsonPath("$.items[2].acceptedAt").exists())
            .andExpect(jsonPath("$.items[2].endsAt").exists())
            .andExpect(jsonPath("$.items[3].auctionId").value(outbid))
            .andExpect(jsonPath("$.items[3].status").value("OUTBID"))
            .andExpect(jsonPath("$.items[3].auctionStatus").value("OPEN"));

        myBids(younghee, "").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items[0].auctionId").value(lost))
            .andExpect(jsonPath("$.items[0].status").value("WON"));
        f.assertInvariants();
    }

    @Test void 상태로_거르고_페이지를_나눈다() throws Exception {
        Long a1 = f.openAuction(seller);
        Long a2 = f.openAuction(seller);
        Long a3 = f.openAuction(seller);
        bid(chulsoo, a1, 50_000);
        bid(younghee, a1, 51_000);
        bid(chulsoo, a2, 50_000);
        bid(younghee, a2, 51_000);
        bid(chulsoo, a3, 50_000);

        myBids(chulsoo, "?status=OUTBID&size=1").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].auctionId").value(a2));
        myBids(chulsoo, "?status=OUTBID&size=1&page=1").andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].auctionId").value(a1));
        myBids(chulsoo, "?status=HIGHEST").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].auctionId").value(a3));
        myBids(chulsoo, "?status=WON").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void 입찰한_적이_없으면_빈_목록() throws Exception {
        f.openAuction(seller);
        myBids(chulsoo, "").andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void 잘못된_상태나_페이지는_400() throws Exception {
        myBids(chulsoo, "?status=LOST").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        myBids(chulsoo, "?size=101").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        myBids(chulsoo, "?page=-1").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/me/bids")).andExpect(status().isUnauthorized());
    }
}
