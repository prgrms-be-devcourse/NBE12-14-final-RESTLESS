package com.restless.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.concert.domain.VenueSeat;
import com.restless.concert.domain.VenueSection;
import com.restless.member.domain.Member;
import com.restless.ticketing.application.TicketSeatQueryService;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("좌석 조회 응답 공유 (ADR-0008)")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false",
        "restless.ticketing.seat-response-cache.enabled=true",
        "restless.ticketing.seat-response-cache.ttl=300ms"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketSeatResponseCacheApiTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");
    private static final String PATH = "/api/performances/{performanceId}/seats";
    private static final String STATUS_PATH = "/api/performances/{performanceId}/seats/status";

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private TicketSeatQueryService seatQueries;
    @Autowired private JsonMapper json;

    private Long userId;
    private Long venueId;
    private Long sectionId;
    private Long concertId;
    private Long layoutId;
    private Long performanceId;

    @BeforeEach
    void setUp() {
        Member host = Member.register("seat-response@example.com", "test-hash", "Host", NOW);
        entityManager.persist(host);
        userId = host.getId();
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", NOW);
        entityManager.persist(venue);
        venueId = venue.getId();
        VenueSection section = VenueSection.create(venueId, "A", "Section A", "1F", "ACTIVE");
        entityManager.persist(section);
        sectionId = section.getId();
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", 1, "Basic", "PUBLISHED", NOW);
        entityManager.persist(layout);
        layoutId = layout.getId();
        Concert concert = Concert.create(venueId, userId, "Concert", "SCHEDULED", NOW);
        entityManager.persist(concert);
        concertId = concert.getId();
        Performance performance = Performance.create(concertId, venueId, layoutId,
                NOW.plusSeconds(14400), NOW.plusSeconds(3600), NOW.plusSeconds(10800), 4, "SCHEDULED");
        entityManager.persist(performance);
        performanceId = performance.getId();
    }

    @Test
    @DisplayName("공유 응답은 서비스 결과를 그대로 직렬화한 JSON이고 gzip을 풀면 같은 본문이다")
    void sharedResponseMatchesServiceJsonAndGzip() throws Exception {
        for (int i = 1; i <= 30; i++) persistSaleSeat(String.valueOf(i));
        entityManager.flush();
        entityManager.clear();
        String expected = json.writeValueAsString(seatQueries.getSeats(performanceId));

        var identity = mockMvc.perform(get(PATH, performanceId).with(jwt()).header(HttpHeaders.ACCEPT_ENCODING, "identity"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
                .andReturn().getResponse();
        assertThat(identity.getContentAsString()).isEqualTo(expected);

        var gzipped = mockMvc.perform(get(PATH, performanceId).with(jwt()).header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andReturn().getResponse();
        assertThat(gzipped.getHeaders(HttpHeaders.VARY)).contains(HttpHeaders.ACCEPT_ENCODING);
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped.getContentAsByteArray()))) {
            assertThat(new String(in.readAllBytes())).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("유효 시간 안에는 이전 상태를 공유하고 지나면 조회 시점의 상태를 준다")
    void sharesStateWithinTtlThenRefreshes() throws Exception {
        TicketSeat seat = persistSaleSeat("1");
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get(PATH, performanceId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("AVAILABLE"));

        hold(seat);

        mockMvc.perform(get(PATH, performanceId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("AVAILABLE"));
        Thread.sleep(400);
        mockMvc.perform(get(PATH, performanceId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("HELD"));
    }

    @Test
    @DisplayName("상태 갱신 응답은 서비스 결과와 같은 JSON이고 gzip으로 줄 수 있으며 유효 시간 안에는 이전 상태를 공유한다")
    void sharesSeatStatusResponse() throws Exception {
        List<TicketSeat> created = new java.util.ArrayList<>();
        for (int i = 1; i <= 80; i++) created.add(persistSaleSeat(String.valueOf(i)));
        entityManager.flush();
        entityManager.clear();
        String expected = json.writeValueAsString(seatQueries.getSeatStates(performanceId));

        var identity = mockMvc.perform(get(STATUS_PATH, performanceId).with(jwt()).header(HttpHeaders.ACCEPT_ENCODING, "identity"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
                .andReturn().getResponse();
        assertThat(identity.getContentAsString()).isEqualTo(expected);
        var gzipped = mockMvc.perform(get(STATUS_PATH, performanceId).with(jwt()).header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andReturn().getResponse();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped.getContentAsByteArray()))) {
            assertThat(new String(in.readAllBytes())).isEqualTo(expected);
        }

        hold(created.get(0));

        mockMvc.perform(get(STATUS_PATH, performanceId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("AVAILABLE"));
        Thread.sleep(400);
        mockMvc.perform(get(STATUS_PATH, performanceId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("HELD"));
    }

    @Test
    @DisplayName("상태 갱신도 없는 회차는 404이고 실패는 공유하지 않는다")
    void seatStatusErrorsAreNotShared() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(STATUS_PATH, -1).with(jwt())).andExpect(status().isNotFound());
        }
        mockMvc.perform(get(STATUS_PATH, performanceId)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 회차와 비공개 공연 회차는 404, 비로그인은 401이며 실패는 공유하지 않는다")
    void errorsAreNotShared() throws Exception {
        Concert draft = Concert.createDraft(venueId, userId, "Draft", "SCHEDULED", NOW,
                null, null, null, null, layoutId, null);
        entityManager.persist(draft);
        Performance hidden = Performance.create(draft.getId(), venueId, layoutId,
                NOW.plusSeconds(14400), NOW.plusSeconds(3600), NOW.plusSeconds(10800), 4, "SCHEDULED");
        entityManager.persist(hidden);
        entityManager.flush();
        entityManager.clear();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(PATH, -1).with(jwt())).andExpect(status().isNotFound());
            mockMvc.perform(get(PATH, hidden.getId()).with(jwt()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mockMvc.perform(get(PATH, performanceId)).andExpect(status().isUnauthorized());
    }

    private TicketSeat persistSaleSeat(String number) {
        VenueSeat physical = VenueSeat.create(venueId, sectionId, "1", number, false, "ACTIVE");
        entityManager.persist(physical);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, physical.getId()), venueId,
                "A", "1", number, new BigDecimal("10.25"), new BigDecimal("-5.25"), true));
        TicketSeat seat = TicketSeat.createAvailable(performanceId, layoutId, physical.getId(), "VIP", 100000);
        entityManager.persist(seat);
        return seat;
    }

    private void hold(TicketSeat seat) {
        TicketReservation reservation = TicketReservation.createHeld(userId, performanceId, "seat-response",
                new byte[32], NOW.plusSeconds(300), NOW);
        entityManager.persist(reservation);
        SeatHold hold = SeatHold.createHeld(reservation.getId(), performanceId, seat.getId(),
                seat.getPrice(), NOW.plusSeconds(300), NOW);
        entityManager.persist(hold);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE performance_seats SET status = 'HELD', current_hold_id = :holdId WHERE id = :seatId")
                .setParameter("holdId", hold.getId()).setParameter("seatId", seat.getId()).executeUpdate();
        entityManager.clear();
    }
}
