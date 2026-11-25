package com.restless.concert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.restless.ticketing.domain.TicketSeat;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
@DisplayName("공연 공개 표식과 고객 노출")
class ConcertPublicationVisibilityTest {
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    private Long customerId;
    private Long venueId;
    private Long concertId;
    private Long performanceId;
    private Long saleSeatId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        Member customer = Member.register(UUID.randomUUID() + "@example.com", "hash", "customer", now);
        entityManager.persist(customer);
        customerId = customer.getId();
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", now);
        entityManager.persist(venue);
        venueId = venue.getId();
        VenueSection section = VenueSection.create(venueId, "A", "A 구역", "1F", "ACTIVE");
        entityManager.persist(section);
        VenueSeat seat = VenueSeat.create(venueId, section.getId(), "1", "1", false, "ACTIVE");
        entityManager.persist(seat);
        VenueLayout layout = VenueLayout.create(venueId, UUID.randomUUID().toString().substring(0, 20), 1, "기본 배치", "PUBLISHED", now);
        entityManager.persist(layout);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layout.getId(), seat.getId()), venueId, "A", "1", "1",
                BigDecimal.ZERO, BigDecimal.ZERO, true));
        Concert concert = Concert.create(venueId, customerId, "공연", "SCHEDULED", now);
        entityManager.persist(concert);
        concertId = concert.getId();
        Performance performance = Performance.create(concertId, venueId, layout.getId(), now.plusSeconds(86400 * 3),
                now.minusSeconds(3600), now.plusSeconds(86400), 2, "SCHEDULED");
        entityManager.persist(performance);
        performanceId = performance.getId();
        TicketSeat ticketSeat = TicketSeat.createAvailable(performanceId, layout.getId(), seat.getId(), "VIP", 1000);
        entityManager.persist(ticketSeat);
        entityManager.flush();
        saleSeatId = ticketSeat.getId();
    }

    @Test
    @DisplayName("공개 여부를 지정하지 않은 공연 행은 기본 공개다")
    void directlyInsertedConcertDefaultsToPublished() {
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, 'direct', 'SCHEDULED', UTC_TIMESTAMP(6))",
                venueId, customerId);
        assertThat(jdbc.queryForObject("SELECT is_published FROM concerts WHERE venue_id=? AND title='direct'", Boolean.class, venueId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM concerts WHERE venue_id=? AND title='direct'", Boolean.class, venueId)).isTrue();
    }

    @Test
    @DisplayName("비공개 공연과 그 회차는 고객 조회·좌석 조회·선점에서 찾을 수 없다")
    void unpublishedConcertIsHiddenFromCustomers() throws Exception {
        var customer = jwt().jwt(token -> token.subject(customerId.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
        mockMvc.perform(get("/api/concerts/{id}", concertId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.venueName").value("Hall"));
        jdbc.update("UPDATE concerts SET is_published=FALSE WHERE id=?", concertId);
        // 선점은 별도 트랜잭션에서 회차 행을 잠그므로 준비 데이터를 커밋한다.
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        try {
            assertHidden(customer);
        } finally {
            jdbc.update("DELETE FROM ticket_purchase_limits WHERE performance_id=?", performanceId);
            jdbc.update("DELETE FROM performance_seats WHERE performance_id=?", performanceId);
            jdbc.update("DELETE FROM performances WHERE id=?", performanceId);
            jdbc.update("DELETE FROM concerts WHERE venue_id=?", venueId);
            jdbc.update("DELETE FROM layout_seats WHERE venue_id=?", venueId);
            jdbc.update("DELETE FROM venue_layouts WHERE venue_id=?", venueId);
            jdbc.update("DELETE FROM venue_seats WHERE venue_id=?", venueId);
            jdbc.update("DELETE FROM venue_sections WHERE venue_id=?", venueId);
            jdbc.update("DELETE FROM venues WHERE id=?", venueId);
            jdbc.update("DELETE FROM users WHERE id=?", customerId);
        }
    }

    private void assertHidden(org.springframework.test.web.servlet.request.RequestPostProcessor customer) throws Exception {
        mockMvc.perform(get("/api/concerts")).andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.concertId==" + concertId + ")]").isEmpty());
        mockMvc.perform(get("/api/concerts/{id}", concertId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/concerts/{id}/performances", concertId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/{id}/seats", performanceId).with(jwt())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/{id}/seats/status", performanceId).with(jwt())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/{id}/booking-availability", performanceId).with(customer)).andExpect(status().isNotFound());
        String reservation = "{\"performanceId\":" + performanceId + ",\"seatIds\":[" + saleSeatId + "]}";
        mockMvc.perform(post("/api/ticket-reservations").with(customer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(reservation))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE performance_id=?", Integer.class, performanceId)).isZero();
    }
}
