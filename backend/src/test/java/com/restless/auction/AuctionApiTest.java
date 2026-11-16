package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class AuctionApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;

    String organizer;
    String otherOrganizer;
    String admin;
    String user;
    Instant startsAt;
    Instant endsAt;

    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM credit_transactions");
        jdbc.update("DELETE FROM credit_holds");
        jdbc.update("DELETE FROM auction_results");
        jdbc.update("DELETE FROM auction_bids");
        jdbc.update("DELETE FROM auctions");
        organizer = token("ORGANIZER", "주최자");
        otherOrganizer = token("ORGANIZER", "다른주최자");
        admin = token("ADMIN", "관리자");
        user = token("USER", "팬");
        startsAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        endsAt = startsAt.plus(1, ChronoUnit.HOURS);
    }

    String token(String role, String nickname) {
        String email = "auction-api-" + UUID.randomUUID() + "@example.com";
        Timestamp t = Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES (?, 'hash', ?, ?, 'ACTIVE', ?, ?)
            """, email, nickname, role, t, t);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + tokenProvider.issue(id, role).value();
    }

    String body(long startPrice, Instant starts, Instant ends, Integer extension) {
        return """
            {"title":"사인 포스터 경매","itemName":"사인 포스터","itemDescription":"친필 사인",
             "startingPriceAmount":%d,"minimumIncrementAmount":1000,
             "startsAt":"%s","endsAt":"%s"%s}
            """.formatted(startPrice, starts, ends, extension == null ? "" : ",\"extensionDurationSeconds\":" + extension);
    }

    ResultActions create(String bearer, String json) throws Exception {
        return mockMvc.perform(post("/api/auctions").header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    Long createdId(String bearer) throws Exception {
        String res = create(bearer, body(10_000, startsAt, endsAt, null))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.auctionId")).longValue();
    }

    void markStarted(Long auctionId) {
        // 엔티티는 UTC로 저장한다(TIMESTAMP_UTC). JVM 시간대와 무관하게 UTC 벽시계 값으로 넣는다.
        LocalDateTime pastUtc = LocalDateTime.ofInstant(Instant.now().minus(1, ChronoUnit.MINUTES), ZoneOffset.UTC);
        jdbc.update("UPDATE auctions SET starts_at = ? WHERE auction_id = ?", pastUtc, auctionId);
    }

    @Test void 주최자가_등록하면_상세에서_조건과_기본값을_볼_수_있다() throws Exception {
        Long id = createdId(organizer);
        mockMvc.perform(get("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auctionId").value(id))
            .andExpect(jsonPath("$.title").value("사인 포스터 경매"))
            .andExpect(jsonPath("$.sellerNickname").value("주최자"))
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.highestBid").doesNotExist())
            .andExpect(jsonPath("$.minimumBidAmount").value(10_000))
            .andExpect(jsonPath("$.extensionDurationSeconds").value(60))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.startsAt").value(startsAt.toString()))
            .andExpect(jsonPath("$.serverTime").isString());
        assertThat(jdbc.queryForObject("SELECT access_policy FROM auctions WHERE auction_id = ?", String.class, id))
            .isEqualTo("ALL");
    }

    @Test void 관리자도_등록할_수_있고_일반_회원은_403() throws Exception {
        createdId(admin);
        create(user, body(10_000, startsAt, endsAt, null))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auctions", Integer.class)).isEqualTo(1);
    }

    @Test void 로그인하지_않으면_등록_수정_삭제는_401() throws Exception {
        mockMvc.perform(post("/api/auctions").contentType(MediaType.APPLICATION_JSON)
            .content(body(10_000, startsAt, endsAt, null))).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/auctions/1").contentType(MediaType.APPLICATION_JSON)
            .content(body(10_000, startsAt, endsAt, null))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/auctions/1")).andExpect(status().isUnauthorized());
    }

    @Test void 입력이_규칙에_맞지_않으면_400() throws Exception {
        create(organizer, body(0, startsAt, endsAt, null)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        create(organizer, body(10_000, startsAt, endsAt, 29)).andExpect(status().isBadRequest());
        create(organizer, body(10_000, startsAt, endsAt, 301)).andExpect(status().isBadRequest());
        create(organizer, body(10_000, Instant.now().minusSeconds(60), endsAt, null)).andExpect(status().isBadRequest());
        create(organizer, body(10_000, startsAt, startsAt, null)).andExpect(status().isBadRequest());
        create(organizer, "{\"title\":\"\"}").andExpect(status().isBadRequest());
        create(organizer, body(10_000, startsAt, endsAt, 300)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auctions", Integer.class)).isEqualTo(1);
    }

    @Test void 본인은_시작_전에_수정할_수_있고_revision이_오른다() throws Exception {
        Long id = createdId(organizer);
        mockMvc.perform(patch("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, organizer)
                .contentType(MediaType.APPLICATION_JSON).content(body(20_000, startsAt, endsAt, 120)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.startingPriceAmount").value(20_000))
            .andExpect(jsonPath("$.minimumBidAmount").value(20_000))
            .andExpect(jsonPath("$.extensionDurationSeconds").value(120))
            .andExpect(jsonPath("$.revision").value(1));
    }

    @Test void 다른_주최자는_수정_삭제_못하고_관리자는_할_수_있다() throws Exception {
        Long id = createdId(organizer);
        mockMvc.perform(patch("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, otherOrganizer)
                .contentType(MediaType.APPLICATION_JSON).content(body(20_000, startsAt, endsAt, null)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUCTION_FORBIDDEN"));
        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, otherOrganizer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUCTION_FORBIDDEN"));

        mockMvc.perform(patch("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body(20_000, startsAt, endsAt, null)))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, admin))
            .andExpect(status().isNoContent());
    }

    @Test void 시작된_경매는_수정_삭제하면_409() throws Exception {
        Long id = createdId(organizer);
        markStarted(id);
        mockMvc.perform(patch("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, organizer)
                .contentType(MediaType.APPLICATION_JSON).content(body(20_000, startsAt, endsAt, null)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_STARTED"));
        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, organizer))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_STARTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auctions WHERE auction_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test void 시작_전에_삭제하면_행이_사라지고_이후_조회는_404() throws Exception {
        Long id = createdId(organizer);
        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, organizer))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
        mockMvc.perform(delete("/api/auctions/" + id).header(HttpHeaders.AUTHORIZATION, organizer))
            .andExpect(status().isNotFound());
    }

    @Test void 목록은_상태로_거르고_페이지를_나눈다() throws Exception {
        Long a = createdId(organizer);
        Long b = createdId(organizer);
        Long c = createdId(organizer);
        jdbc.update("UPDATE auctions SET status = 'OPEN' WHERE auction_id = ?", c);

        mockMvc.perform(get("/api/auctions").param("status", "SCHEDULED").header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].auctionId").value(a))
            .andExpect(jsonPath("$.items[1].auctionId").value(b));
        mockMvc.perform(get("/api/auctions").param("status", "OPEN").header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].status").value("OPEN"));
        mockMvc.perform(get("/api/auctions").param("size", "2").param("page", "1").header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/auctions").param("status", "NOPE").header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auctions").param("size", "101").header(HttpHeaders.AUTHORIZATION, user))
            .andExpect(status().isBadRequest());
    }
}
