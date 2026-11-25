package com.restless.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import com.restless.concert.domain.VenueSeat;
import com.restless.concert.domain.VenueSection;
import com.restless.concert.domain.ConcertSectionPrice;
import com.restless.concert.domain.Performance;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.member.domain.Member;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
@DisplayName("주최자 공연 등록과 관리 권한")
class OrganizerConcertApiTest {
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    private Long ownerId;
    private Long otherId;
    private Long adminId;
    private Long userId;
    private Long venueId;
    private Long customerId;

    @BeforeEach
    void setUp() {
        ownerId = member("ORGANIZER");
        otherId = member("ORGANIZER");
        adminId = member("ADMIN");
        userId = member("USER");
        customerId = member("USER");
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", Instant.now());
        entityManager.persist(venue);
        venueId = venue.getId();
        entityManager.flush();
        entityManager.clear();
    }

    private Long member(String role) {
        Member member = Member.register(UUID.randomUUID() + "@example.com", "hash", "member", Instant.now());
        entityManager.persist(member);
        entityManager.flush();
        jdbc.update("UPDATE users SET role=? WHERE id=?", role, member.getId());
        return member.getId();
    }

    private RequestPostProcessor actor(Long id, String role) {
        return jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    @DisplayName("주최자의 공연 등록은 로그인한 회원을 소유자로 저장하고 공개 조회에 노출한다")
    void createsConcertOwnedByAuthenticatedOrganizer() throws Exception {
        Long selectedLayout = layoutFor(venueId);
        String body = "{\"venueId\":" + venueId + ",\"layoutId\":" + selectedLayout + ",\"title\":\"봄 공연\",\"sellerId\":" + otherId
                + ",\"description\":\"공연 소개\",\"periodStartDate\":\"2027-03-01\",\"periodEndDate\":\"2027-03-03\",\"durationMinutes\":120}";
        var result = mockMvc.perform(post("/api/organizer/concerts").with(actor(ownerId, "ORGANIZER"))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.organizerId").value(ownerId))
                .andExpect(jsonPath("$.venueId").value(venueId)).andExpect(jsonPath("$.layoutId").value(selectedLayout)).andExpect(jsonPath("$.published").value(false)).andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.venueName").value("Hall")).andExpect(jsonPath("$.venueAddress").value("Seoul"))
                .andExpect(jsonPath("$.description").value("공연 소개")).andExpect(jsonPath("$.periodStartDate").value("2027-03-01"))
                .andExpect(jsonPath("$.periodEndDate").value("2027-03-03")).andExpect(jsonPath("$.durationMinutes").value(120))
                .andReturn();
        Number concertId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.concertId");
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT seller_id FROM concerts WHERE id=?", Long.class, concertId.longValue())).isEqualTo(ownerId);
        mockMvc.perform(get("/api/concerts/{id}", concertId.longValue())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/concerts/{id}/performances", concertId.longValue())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/concerts")).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/organizer/concerts/{id}", concertId.longValue()).with(actor(ownerId, "ORGANIZER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organizerId").value(ownerId));
        mockMvc.perform(get("/api/organizer/concerts/{id}", concertId.longValue()).with(actor(adminId, "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/organizer/concerts/{id}", concertId.longValue()).with(actor(otherId, "ORGANIZER")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizer/concerts/{id}", Long.MAX_VALUE).with(actor(otherId, "ORGANIZER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증·역할과 활성 회원을 확인하고 관리자의 등록도 허용한다")
    void checksAccessAndAllowsAdminCreation() throws Exception {
        String body = "{\"venueId\":" + venueId + ",\"layoutId\":" + layoutFor(venueId) + ",\"title\":\"공연\"}";
        mockMvc.perform(post("/api/organizer/concerts").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/organizer/concerts").with(actor(userId, "USER"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizer/concerts").with(actor(userId, "ORGANIZER"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/organizer/concerts").with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId").value(adminId));
    }

    @Test
    @DisplayName("공연장과 제목 입력 오류는 등록하지 않는다")
    void rejectsInvalidVenueAndTitle() throws Exception {
        for (String body : new String[]{"{}", "{\"venueId\":0,\"title\":\"공연\"}",
                "{\"venueId\":" + venueId + ",\"title\":\"  \"}",
                "{\"venueId\":" + venueId + ",\"title\":\"" + "가".repeat(201) + "\"}"}) {
            mockMvc.perform(post("/api/organizer/concerts").with(actor(ownerId, "ORGANIZER"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/organizer/concerts").with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content("{\"venueId\":9223372036854775807,\"layoutId\":1,\"title\":\"공연\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concerts WHERE seller_id=?", Integer.class, ownerId)).isZero();
    }

    @Test
    @DisplayName("공연 기간은 시작·종료를 함께 입력하고 순서를 지키며 소요 시간은 양수다")
    void rejectsInvalidPeriodAndDuration() throws Exception {
        for (String extra : new String[]{",\"periodStartDate\":\"2027-03-01\"",
                ",\"periodStartDate\":\"2027-03-03\",\"periodEndDate\":\"2027-03-01\"",
                ",\"durationMinutes\":0", ",\"durationMinutes\":-1"}) {
            String body = "{\"venueId\":" + venueId + ",\"title\":\"공연\"" + extra + "}";
            mockMvc.perform(post("/api/organizer/concerts").with(actor(ownerId, "ORGANIZER"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concerts WHERE seller_id=?", Integer.class, ownerId)).isZero();
    }

    private Long concertWithPeriod(LocalDate start, LocalDate end) {
        return concertWithPeriod(start, end, layoutFor(venueId));
    }

    private Long concertWithPeriod(LocalDate start, LocalDate end, Long layoutId) {
        return concertWithPeriod(start, end, layoutId, true);
    }

    private Long concertWithPeriod(LocalDate start, LocalDate end, Long layoutId, boolean withPrice) {
        Concert concert = Concert.createDraft(venueId, ownerId, "공연", "SCHEDULED", Instant.now(), null, start, end, 120, layoutId);
        entityManager.persist(concert);
        entityManager.flush();
        if (withPrice) {
            entityManager.persist(ConcertSectionPrice.create(concert.getId(), "A", "R", 100L));
            entityManager.flush();
        }
        return concert.getId();
    }

    private Long layoutFor(Long id) {
        VenueLayout layout = VenueLayout.create(id, UUID.randomUUID().toString().substring(0, 20), 1,
                "기본 배치", "PUBLISHED", Instant.now());
        entityManager.persist(layout);
        entityManager.flush();
        return layout.getId();
    }

    private void addSellableSeat(Long layoutId) {
        VenueSection section = VenueSection.create(venueId, UUID.randomUUID().toString().substring(0, 8), "A", "1F", "ACTIVE");
        entityManager.persist(section);
        VenueSeat seat = VenueSeat.create(venueId, section.getId(), "1", "1", false, "ACTIVE");
        entityManager.persist(seat);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, seat.getId()), venueId, "A", "1", "1", BigDecimal.ZERO, BigDecimal.ZERO, true));
        entityManager.flush();
    }

    private String performanceBody(String startsAt, String opensAt, String closesAt, int maximum) {
        return "{\"startsAt\":\"" + startsAt
                + "\",\"bookingOpensAt\":\"" + opensAt + "\",\"bookingClosesAt\":\"" + closesAt
                + "\",\"maxTicketsPerUser\":" + maximum + "}";
    }

    @Test
    @DisplayName("공통 구역 가격은 판매 구역을 모두 채워야 하고 ADMIN도 설정할 수 있다")
    void configuresCommonSectionPrices() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId, false);
        String path = "/api/organizer/concerts/{id}/section-prices";
        String body = "{\"sections\":[{\"sectionLabel\":\"A\",\"grade\":\"VIP\",\"price\":1000}]}";
        mockMvc.perform(put(path, concertId).with(actor(otherId, "ORGANIZER")).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(body.replace("\"A\"", "\"B\""))).andExpect(status().isBadRequest());
        mockMvc.perform(put(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(body.replace("1000", "0"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concert_section_prices WHERE concert_id=?", Integer.class, concertId)).isZero();
        mockMvc.perform(put(path, concertId).with(actor(adminId, "ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sectionLabel").value("A")).andExpect(jsonPath("$[0].price").value(1000));
        mockMvc.perform(put(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(body.replace("1000", "2000").replace("VIP", "R"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].grade").value("R"));
        assertThat(jdbc.queryForObject("SELECT price FROM concert_section_prices WHERE concert_id=?", Long.class, concertId)).isEqualTo(2000L);
        entityManager.flush();
        jdbc.update("UPDATE concerts SET is_published=TRUE WHERE id=?", concertId);
        entityManager.clear();
        mockMvc.perform(put(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("주최자는 본인 공연장의 배치와 판매 가능한 구역별 좌석 수를 조회한다")
    void listsOwnVenueLayoutsAndSellableSections() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        VenueSection section = VenueSection.create(venueId, "A", "A 구역", "1F", "ACTIVE");
        entityManager.persist(section);
        for (int number = 1; number <= 2; number++) {
            VenueSeat seat = VenueSeat.create(venueId, section.getId(), "1", String.valueOf(number), false, "ACTIVE");
            entityManager.persist(seat);
            entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, seat.getId()), venueId,
                    number == 1 ? "A" : "B", "1", String.valueOf(number), BigDecimal.ZERO, BigDecimal.ZERO, number == 1));
        }
        entityManager.flush(); entityManager.clear();
        String path = "/api/organizer/concerts/{id}/layouts";
        mockMvc.perform(get(path, concertId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path, concertId).with(actor(otherId, "ORGANIZER"))).andExpect(status().isNotFound());
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].layoutId").value(layoutId))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].sectionLabel").value("A"))
                .andExpect(jsonPath("$[0].sections[0].sellableSeats").value(1));
    }

    @Test
    @DisplayName("내 공연만 최신순으로 페이지 조회하고 권한과 페이지 범위를 확인한다")
    void listsOnlyOwnConcertsWithPagination() throws Exception {
        Concert older = Concert.create(venueId, ownerId, "이전", "SCHEDULED", Instant.parse("2026-01-01T00:00:00Z"));
        Concert newer = Concert.create(venueId, ownerId, "최근", "SCHEDULED", Instant.parse("2026-02-01T00:00:00Z"));
        entityManager.persist(older); entityManager.persist(newer);
        entityManager.persist(Concert.create(venueId, otherId, "타인", "SCHEDULED", Instant.now()));
        entityManager.flush(); entityManager.clear();
        String path = "/api/organizer/concerts";
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(actor(userId, "USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(actor(ownerId, "ORGANIZER")).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.content[0].concertId").value(newer.getId()));
        mockMvc.perform(get(path).with(actor(ownerId, "ORGANIZER")).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].concertId").value(older.getId()));
        mockMvc.perform(get(path).with(actor(adminId, "ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get(path).with(actor(ownerId, "ORGANIZER")).param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("회차 등록 순서와 무관하게 시작 시각 순으로 소요 시간 겹침을 검사한다")
    void durationChangesUseStartTimeOrder() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        entityManager.persist(Performance.create(concertId, venueId, layoutId, Instant.parse("2027-03-01T03:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), Instant.parse("2027-02-28T00:00:00Z"), 2, "SCHEDULED"));
        entityManager.persist(Performance.create(concertId, venueId, layoutId, Instant.parse("2027-03-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), Instant.parse("2027-02-28T00:00:00Z"), 2, "SCHEDULED"));
        entityManager.flush();
        String body = "{\"title\":\"공연\",\"periodStartDate\":\"2027-03-01\",\"periodEndDate\":\"2027-03-03\",\"durationMinutes\":180}";
        mockMvc.perform(put("/api/organizer/concerts/{id}", concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mockMvc.perform(put("/api/organizer/concerts/{id}", concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(body.replace("180", "181"))).andExpect(status().isBadRequest());
    }

}
