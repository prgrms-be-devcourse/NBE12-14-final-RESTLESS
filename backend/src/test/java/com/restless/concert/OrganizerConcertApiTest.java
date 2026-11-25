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
    @DisplayName("공통 설정된 초안에 회차와 판매 좌석을 등록하고 고객에게는 숨긴다")
    void registersOwnedPerformanceAndChecksRoles() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId, false);
        VenueSection section = VenueSection.create(venueId, "A", "A", "1F", "ACTIVE");
        entityManager.persist(section);
        VenueSeat physicalSeat = VenueSeat.create(venueId, section.getId(), "1", "1", false, "ACTIVE");
        entityManager.persist(physicalSeat);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, physicalSeat.getId()), venueId,
                "A", "1", "1", BigDecimal.ZERO, BigDecimal.ZERO, true));
        entityManager.flush();
        entityManager.persist(ConcertSectionPrice.create(concertId, "A", "VIP", 100L));
        entityManager.flush();
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        String body = performanceBody("2027-02-28T15:00:00Z", "2027-02-20T00:00:00Z", "2027-02-28T15:00:00Z", 3);
        String path = "/api/organizer/concerts/{id}/performances";
        mockMvc.perform(post(path, concertId).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, concertId).with(actor(userId, "USER")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        for (var actor : new RequestPostProcessor[]{actor(otherId, "ORGANIZER")}) {
            mockMvc.perform(post(path, concertId).with(actor).contentType("application/json").content(body)).andExpect(status().isNotFound());
        }
        var result = mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER"))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.concertId").value(concertId))
                .andExpect(jsonPath("$.venueId").value(venueId)).andExpect(jsonPath("$.layoutId").value(layoutId))
                .andExpect(jsonPath("$.startsAt").value("2027-02-28T15:00:00Z"))
                .andExpect(jsonPath("$.status").value("SCHEDULED")).andReturn();
        Number performanceId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.performanceId");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=?", Integer.class, performanceId.longValue())).isEqualTo(1);
        mockMvc.perform(get("/api/concerts/{id}/performances", concertId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/{id}/seats", performanceId.longValue()).with(jwt())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/{id}/seats/status", performanceId.longValue()).with(jwt())).andExpect(status().isNotFound());
        Long saleSeatId = jdbc.queryForObject("SELECT id FROM performance_seats WHERE performance_id=?", Long.class, performanceId.longValue());
        String reservation = "{\"performanceId\":" + performanceId.longValue() + ",\"seatIds\":[" + saleSeatId + "]}";
        mockMvc.perform(post("/api/ticket-reservations").with(actor(customerId, "USER"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(reservation))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM performance_seats WHERE performance_id=?", performanceId.longValue());
        jdbc.update("DELETE FROM performances WHERE id=?", performanceId.longValue());
        jdbc.update("DELETE FROM concert_section_prices WHERE concert_id=?", concertId);
        jdbc.update("DELETE FROM concerts WHERE id=?", concertId);
    }

    @Test
    @DisplayName("공연장 시간대의 기간 경계와 예매 시간 순서를 검증한다")
    void validatesPerformanceAndBookingTimes() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        addSellableSeat(layoutId);
        String path = "/api/organizer/concerts/{id}/performances";
        String opens = "2027-02-20T00:00:00Z", closes = "2027-02-25T00:00:00Z";
        for (String start : new String[]{"2027-02-28T14:59:59Z", "2027-03-03T15:00:00Z"}) {
            mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                    .content(performanceBody(start, opens, closes, 3))).andExpect(status().isBadRequest());
        }
        for (String body : new String[]{
                performanceBody("2027-03-03T14:59:59Z", closes, closes, 3),
                performanceBody("2027-03-01T00:00:00Z", opens, "2027-03-01T00:00:01Z", 3),
                performanceBody("2027-03-01T00:00:00Z", opens, closes, 0)}) {
            mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody("2027-03-03T14:59:59Z", opens, closes, 3))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("공연 기간과 같은 공연장의 등록된 배치가 없으면 회차를 생성하지 않는다")
    void requiresConcertPeriodAndVenueLayout() throws Exception {
        Long validLayout = layoutFor(venueId);
        addSellableSeat(validLayout);
        Long missingPeriod = concertWithPeriod(null, null, validLayout);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), validLayout);
        Venue anotherVenue = Venue.create("Other", "Seoul", "Asia/Seoul", "ACTIVE", Instant.now());
        entityManager.persist(anotherVenue);
        Long otherLayout = layoutFor(anotherVenue.getId());
        String path = "/api/organizer/concerts/{id}/performances";
        String starts = "2027-03-01T00:00:00Z", opens = "2027-02-20T00:00:00Z", closes = "2027-02-25T00:00:00Z";
        mockMvc.perform(post(path, missingPeriod).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody(starts, opens, closes, 3))).andExpect(status().isBadRequest());
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody(starts, opens, closes, 3))).andExpect(status().isCreated());
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody("2027-03-02T00:00:00Z", opens, closes, 3))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats ps JOIN performances p ON p.id=ps.performance_id WHERE p.concert_id=?", Integer.class, concertId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performances WHERE concert_id=?", Integer.class, concertId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performances WHERE concert_id=?", Integer.class, missingPeriod)).isZero();
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
    @DisplayName("본인 회차의 좌석과 구역 가격 설정 상태를 조회하고 불일치를 구분한다")
    void listsPerformanceSetupAndDetectsInconsistentSeats() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        VenueSection section = VenueSection.create(venueId, "A", "A 구역", "1F", "ACTIVE");
        entityManager.persist(section);
        VenueSeat seat = VenueSeat.create(venueId, section.getId(), "1", "1", false, "ACTIVE");
        entityManager.persist(seat);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, seat.getId()), venueId,
                "A", "1", "1", BigDecimal.ZERO, BigDecimal.ZERO, true));
        VenueSeat second = VenueSeat.create(venueId, section.getId(), "1", "2", false, "ACTIVE");
        entityManager.persist(second);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, second.getId()), venueId,
                "A", "1", "2", BigDecimal.ZERO, BigDecimal.ZERO, true));
        Performance performance = Performance.create(concertId, venueId, layoutId, Instant.parse("2027-03-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), Instant.parse("2027-02-28T00:00:00Z"), 4, "SCHEDULED");
        entityManager.persist(performance); entityManager.flush(); entityManager.clear();
        String path = "/api/organizer/concerts/{id}/performances";
        mockMvc.perform(get(path, concertId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path, concertId).with(actor(otherId, "ORGANIZER"))).andExpect(status().isNotFound());
        mockMvc.perform(get(path, concertId).with(actor(adminId, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].performance.performanceId").value(performance.getId()))
                .andExpect(jsonPath("$[0].pricingStatus").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$[0].sellableSeatCount").value(2));
        entityManager.persist(TicketSeat.createAvailable(performance.getId(), layoutId, seat.getId(), "VIP", 150000));
        entityManager.flush(); entityManager.clear();
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pricingStatus").value("INCONSISTENT"));
        entityManager.persist(TicketSeat.createAvailable(performance.getId(), layoutId, second.getId(), "VIP", 120000));
        entityManager.flush(); entityManager.clear();
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pricingStatus").value("INCONSISTENT"))
                .andExpect(jsonPath("$[0].sections[0].uniform").value(false));
        jdbc.update("UPDATE performance_seats SET price=150000 WHERE performance_id=?", performance.getId());
        entityManager.clear();
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pricingStatus").value("CONFIGURED"))
                .andExpect(jsonPath("$[0].configuredSeatCount").value(2))
                .andExpect(jsonPath("$[0].sections[0].sectionLabel").value("A"))
                .andExpect(jsonPath("$[0].sections[0].price").value(150000));
        jdbc.update("UPDATE layout_seats SET is_sellable=FALSE WHERE layout_id=? AND venue_seat_id=?", layoutId, seat.getId());
        entityManager.clear();
        mockMvc.perform(get(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pricingStatus").value("INCONSISTENT"));
    }

    @Test
    @DisplayName("모든 회차 준비 후 관리자가 공개하면 고객이 조회하며 중복 공개는 최초 시각을 유지한다")
    void publishesAllPreparedPerformances() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        String path = "/api/organizer/concerts/{id}/performances";
        String opens = "2027-02-20T00:00:00Z", closes = "2027-02-25T00:00:00Z";
        for (String start : new String[]{"2027-03-01T00:00:00Z", "2027-03-02T00:00:00Z"}) {
            mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER"))
                    .contentType("application/json").content(performanceBody(start, opens, closes, 3)))
                    .andExpect(status().isCreated());
        }
        String publishPath = "/api/organizer/concerts/{id}/publish";
        mockMvc.perform(post(publishPath, concertId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(publishPath, concertId).with(actor(otherId, "ORGANIZER"))).andExpect(status().isNotFound());
        var result = mockMvc.perform(post(publishPath, concertId).with(actor(adminId, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.publishedAt").exists()).andReturn();
        String publishedAt = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.publishedAt");
        mockMvc.perform(post(publishPath, concertId).with(actor(ownerId, "ORGANIZER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishedAt").value(publishedAt));
        mockMvc.perform(get("/api/concerts/{id}", concertId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/concerts/{id}/performances", concertId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("공연 공개와 회차 추가가 동시에 요청돼도 공개된 회차는 모두 완전히 준비된다")
    void publicationAndPerformanceCreationSerialize() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        String performancePath = "/api/organizer/concerts/{id}/performances";
        String opens = "2027-02-20T00:00:00Z", closes = "2027-02-25T00:00:00Z";
        mockMvc.perform(post(performancePath, concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(performanceBody("2027-03-01T00:00:00Z", opens, closes, 3)))
                .andExpect(status().isCreated());
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var publication = executor.submit(() -> {
                ready.countDown(); start.await();
                return mockMvc.perform(post("/api/organizer/concerts/{id}/publish", concertId).with(actor(ownerId, "ORGANIZER")))
                        .andReturn().getResponse().getStatus();
            });
            var creation = executor.submit(() -> {
                ready.countDown(); start.await();
                return mockMvc.perform(post(performancePath, concertId).with(actor(ownerId, "ORGANIZER"))
                        .contentType("application/json").content(performanceBody("2027-03-02T00:00:00Z", opens, closes, 3)))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(publication.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(creation.get(20, java.util.concurrent.TimeUnit.SECONDS)).isIn(201, 400);
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM performances WHERE concert_id=?", Integer.class, concertId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats s JOIN performances p ON p.id=s.performance_id WHERE p.concert_id=?", Integer.class, concertId)).isEqualTo(count);
            assertThat(jdbc.queryForObject("SELECT is_published FROM concerts WHERE id=?", Boolean.class, concertId)).isTrue();
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE s FROM performance_seats s JOIN performances p ON p.id=s.performance_id WHERE p.concert_id=?", concertId);
            jdbc.update("DELETE FROM performances WHERE concert_id=?", concertId);
            jdbc.update("DELETE FROM concert_section_prices WHERE concert_id=?", concertId);
            jdbc.update("DELETE FROM concerts WHERE id=?", concertId);
        }
    }

    @Test
    @DisplayName("같은 시작 시각의 단건 회차 등록이 동시에 요청돼도 하나만 생성된다")
    void concurrentSingleCreationsCannotOverlap() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        String body = performanceBody("2027-03-01T00:00:00Z", "2027-02-20T00:00:00Z", "2027-02-25T00:00:00Z", 2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> create = () -> { ready.countDown(); start.await();
                return mockMvc.perform(post("/api/organizer/concerts/{id}/performances", concertId).with(actor(ownerId, "ORGANIZER"))
                        .contentType("application/json").content(body)).andReturn().getResponse().getStatus(); };
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performances WHERE concert_id=?", Integer.class, concertId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats s JOIN performances p ON p.id=s.performance_id WHERE p.concert_id=?", Integer.class, concertId)).isEqualTo(1);
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE s FROM performance_seats s JOIN performances p ON p.id=s.performance_id WHERE p.concert_id=?", concertId);
            jdbc.update("DELETE FROM performances WHERE concert_id=?", concertId);
            jdbc.update("DELETE FROM concert_section_prices WHERE concert_id=?", concertId);
            jdbc.update("DELETE FROM concerts WHERE id=?", concertId);
        }
    }

    @Test
    @DisplayName("한 회차라도 예매 시작이 지났거나 좌석 가격이 불일치하면 전체 공개되지 않는다")
    void rejectsIncompletePublication() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        String path = "/api/organizer/concerts/{id}/publish";
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isBadRequest());
        String performancePath = "/api/organizer/concerts/{id}/performances";
        for (String start : new String[]{"2027-03-01T00:00:00Z", "2027-03-02T00:00:00Z"}) {
            mockMvc.perform(post(performancePath, concertId).with(actor(ownerId, "ORGANIZER"))
                    .contentType("application/json").content(performanceBody(start, "2027-02-20T00:00:00Z", "2027-02-25T00:00:00Z", 3)))
                    .andExpect(status().isCreated());
        }
        entityManager.flush(); entityManager.clear();
        jdbc.update("UPDATE performances SET booking_opens_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE concert_id=? ORDER BY id DESC LIMIT 1", concertId);
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isBadRequest());
        entityManager.clear();
        jdbc.update("UPDATE performances SET booking_opens_at='2027-02-20 00:00:00' WHERE concert_id=?", concertId);
        jdbc.update("UPDATE performance_seats SET price=101 WHERE performance_id=(SELECT MAX(id) FROM performances WHERE concert_id=?)", concertId);
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT is_published FROM concerts WHERE id=?", Boolean.class, concertId)).isFalse();
        mockMvc.perform(get("/api/concerts/{id}", concertId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("관리자가 공통 가격을 바꾸고 회차 간 겹침을 차단하며 맞닿는 회차는 허용한다")
    void adminSetsPricesAndPerformanceOverlapIsRejected() throws Exception {
        Long layoutId = layoutFor(venueId);
        addSellableSeat(layoutId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId, false);
        String prices = "{\"sections\":[{\"sectionLabel\":\"A\",\"grade\":\"VIP\",\"price\":100}]}";
        String pricePath = "/api/organizer/concerts/{id}/section-prices";
        mockMvc.perform(put(pricePath, concertId).with(actor(otherId, "ORGANIZER"))
                .contentType("application/json").content(prices)).andExpect(status().isNotFound());
        mockMvc.perform(put(pricePath, concertId).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(prices.replace("100", "0"))).andExpect(status().isBadRequest());
        mockMvc.perform(put(pricePath, concertId).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(prices)).andExpect(status().isOk());
        mockMvc.perform(put(pricePath, concertId).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(prices.replace("100", "200"))).andExpect(status().isOk());
        String path = "/api/organizer/concerts/{id}/performances";
        String opens = "2027-02-20T00:00:00Z", closes = "2027-02-25T00:00:00Z";
        mockMvc.perform(post(path, concertId).with(actor(adminId, "ADMIN")).contentType("application/json")
                .content(performanceBody("2027-03-01T00:00:00Z", opens, closes, 2))).andExpect(status().isCreated());
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody("2027-03-01T01:59:59Z", opens, closes, 2))).andExpect(status().isBadRequest());
        mockMvc.perform(post(path, concertId).with(actor(ownerId, "ORGANIZER")).contentType("application/json")
                .content(performanceBody("2027-03-01T02:00:00Z", opens, closes, 2))).andExpect(status().isCreated());
        mockMvc.perform(put(pricePath, concertId).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(prices)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats ps JOIN performances p ON p.id=ps.performance_id WHERE p.concert_id=? AND ps.price=200", Integer.class, concertId)).isEqualTo(2);
    }

    @Test
    @DisplayName("판매 구역 설정이 일치하지 않으면 회차와 좌석을 남기지 않는다")
    void rollsBackPerformanceWhenSeatPreparationFails() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        mockMvc.perform(post("/api/organizer/concerts/{id}/performances", concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(performanceBody("2027-03-01T00:00:00Z", "2027-02-20T00:00:00Z", "2027-02-25T00:00:00Z", 2)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performances WHERE concert_id=?", Integer.class, concertId)).isZero();
        jdbc.update("DELETE FROM concert_section_prices WHERE concert_id=?", concertId);
        jdbc.update("DELETE FROM concerts WHERE id=?", concertId);
    }

    @Test
    @DisplayName("공개된 공연은 관리자도 일정과 회차를 변경할 수 없지만 제목은 수정할 수 있다")
    void publishedConcertOnlyAllowsTitleAndDescriptionChanges() throws Exception {
        Long layoutId = layoutFor(venueId);
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), layoutId);
        Concert concert = entityManager.find(Concert.class, concertId);
        concert.publish(Instant.now());
        entityManager.flush();
        String path = "/api/organizer/concerts/{id}";
        String body = "{\"title\":\"새 제목\",\"periodStartDate\":\"2027-03-01\",\"periodEndDate\":\"2027-03-03\",\"durationMinutes\":120}";
        mockMvc.perform(put(path, concertId).with(actor(adminId, "ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(put(path, concertId).with(actor(adminId, "ADMIN")).contentType("application/json")
                .content(body.replace("120", "90"))).andExpect(status().isBadRequest());
        mockMvc.perform(post(path + "/performances", concertId).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(performanceBody("2027-03-01T00:00:00Z", "2027-02-20T00:00:00Z", "2027-02-25T00:00:00Z", 2)))
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

    @Test
    @DisplayName("공연과 회차 수정은 본인 권한과 기간·예매 시각을 검증한다")
    void updatesConcertAndPerformanceWithScheduleLimits() throws Exception {
        Long concertId = concertWithPeriod(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3));
        Long layoutId = layoutFor(venueId);
        Performance performance = Performance.create(concertId, venueId, layoutId, Instant.parse("2027-03-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), Instant.parse("2027-02-28T00:00:00Z"), 4, "SCHEDULED");
        entityManager.persist(performance); entityManager.flush(); entityManager.clear();
        String concertPath = "/api/organizer/concerts/{id}";
        String concertBody = "{\"title\":\"변경\",\"description\":\"소개\",\"periodStartDate\":\"2027-03-01\",\"periodEndDate\":\"2027-03-04\",\"durationMinutes\":120}";
        mockMvc.perform(put(concertPath, concertId).with(actor(otherId, "ORGANIZER"))
                .contentType("application/json").content(concertBody)).andExpect(status().isNotFound());
        mockMvc.perform(put(concertPath, concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(concertBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("변경")).andExpect(jsonPath("$.durationMinutes").value(120));
        String path = "/api/organizer/performances/{id}";
        String body = "{\"startsAt\":\"2027-03-02T00:00:00Z\",\"bookingOpensAt\":\"2027-02-02T00:00:00Z\",\"bookingClosesAt\":\"2027-03-01T00:00:00Z\",\"maxTicketsPerUser\":2}";
        mockMvc.perform(put(path, performance.getId()).with(actor(adminId, "ADMIN"))
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mockMvc.perform(put(path, performance.getId()).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxTicketsPerUser").value(2)).andExpect(jsonPath("$.startsAt").value("2027-03-02T00:00:00Z"));
        String excluded = "{\"title\":\"잘못된 기간\",\"periodStartDate\":\"2027-03-03\",\"periodEndDate\":\"2027-03-04\"}";
        mockMvc.perform(put(concertPath, concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(excluded)).andExpect(status().isBadRequest());
        entityManager.flush(); entityManager.clear();
        jdbc.update("UPDATE performances SET booking_opens_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id=?", performance.getId());
        mockMvc.perform(put(path, performance.getId()).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(body.replace("\"maxTicketsPerUser\":2", "\"maxTicketsPerUser\":3")))
                .andExpect(status().isOk());
        entityManager.flush(); entityManager.clear();
        jdbc.update("UPDATE performances SET booking_opens_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id=?", performance.getId());
        mockMvc.perform(put(concertPath, concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(concertBody.replace("\"durationMinutes\":120", "\"durationMinutes\":90")))
                .andExpect(status().isConflict());
        mockMvc.perform(put(concertPath, concertId).with(actor(ownerId, "ORGANIZER"))
                .contentType("application/json").content(concertBody.replace("변경", "오픈 후 소개 수정")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("오픈 후 소개 수정"));
    }
}
