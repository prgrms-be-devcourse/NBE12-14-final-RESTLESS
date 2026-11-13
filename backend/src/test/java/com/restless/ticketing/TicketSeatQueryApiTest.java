package com.restless.ticketing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("회차별 좌석 조회 API")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketSeatQueryApiTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");
    private static final String PATH = "/api/performances/{performanceId}/seats";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long venueId;
    private Long sectionId;
    private Long concertId;
    private Long layoutId;
    private Long performanceId;

    @BeforeEach
    void setUp() {
        Member host = Member.register("seat-query@example.com", "test-hash", "Host", NOW);
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
        performanceId = persistPerformance(layoutId);
    }

    @Test
    @DisplayName("로그인한 사용자는 지정 회차의 배치 위치·가격·현재 상태만 좌석 ID 순으로 조회한다")
    void returnsOnlyRequestedPerformanceSeatsWithTheirLayoutAndCurrentState() throws Exception {
        Long firstPhysicalId = persistPhysicalSeat("1");
        Long secondPhysicalId = persistPhysicalSeat("2");
        Long unsoldPhysicalId = persistPhysicalSeat("3");
        includeInLayout(layoutId, firstPhysicalId, "A", "10.25");
        includeInLayout(layoutId, secondPhysicalId, "B", "20.50");
        includeInLayout(layoutId, unsoldPhysicalId, "C", "30.00");

        // 같은 물리 좌석도 배치에 따라 표시명과 위치가 다를 수 있다.
        VenueLayout otherLayout = VenueLayout.create(venueId, "BASIC", 2, "Other", "PUBLISHED", NOW);
        entityManager.persist(otherLayout);
        includeInLayout(otherLayout.getId(), firstPhysicalId, "OTHER", "999.00");
        Long otherPerformanceId = persistPerformance(otherLayout.getId());
        persistTicketSeat(otherPerformanceId, otherLayout.getId(), firstPhysicalId, "OTHER", 1);
        Long sharedLayoutPerformanceId = persistPerformance(layoutId);
        persistTicketSeat(sharedLayoutPerformanceId, layoutId, firstPhysicalId, "OTHER", 2);

        // 물리 좌석 순서와 판매 좌석 ID 순서를 다르게 하여 위치 결합 키를 검증한다.
        TicketSeat first = persistTicketSeat(performanceId, layoutId, secondPhysicalId, "R", 0);
        TicketSeat second = persistTicketSeat(performanceId, layoutId, firstPhysicalId, "VIP", 3_000_000_000L);
        TicketReservation reservation = TicketReservation.createHeld(userId, performanceId, "seat-query",
                new byte[32], NOW.plusSeconds(300), NOW);
        entityManager.persist(reservation);
        SeatHold hold = SeatHold.createHeld(reservation.getId(), performanceId, second.getId(),
                second.getPrice(), NOW.plusSeconds(300), NOW);
        entityManager.persist(hold);
        entityManager.flush();
        // 실제 선점 기능 대신 기존 소유권 제약을 만족하는 조회용 상태를 준비한다.
        entityManager.createNativeQuery("""
                UPDATE performance_seats SET status = 'HELD', current_hold_id = :holdId WHERE id = :seatId
                """).setParameter("holdId", hold.getId()).setParameter("seatId", second.getId()).executeUpdate();
        entityManager.clear();

        mockMvc.perform(get(PATH, performanceId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].seatId").value(first.getId()))
                .andExpect(jsonPath("$[0].venueSeatId").value(secondPhysicalId))
                .andExpect(jsonPath("$[0].sectionLabel").value("B"))
                .andExpect(jsonPath("$[0].rowLabel").value("1"))
                .andExpect(jsonPath("$[0].seatNumber").value("2"))
                .andExpect(jsonPath("$[0].posX").value(20.50))
                .andExpect(jsonPath("$[0].posY").value(-5.25))
                .andExpect(jsonPath("$[0].grade").value("R"))
                .andExpect(jsonPath("$[0].price").value(0))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[1].seatId").value(second.getId()))
                .andExpect(jsonPath("$[1].venueSeatId").value(firstPhysicalId))
                .andExpect(jsonPath("$[1].sectionLabel").value("A"))
                .andExpect(jsonPath("$[1].posX").value(10.25))
                .andExpect(jsonPath("$[1].grade").value("VIP"))
                .andExpect(jsonPath("$[1].price").value(3_000_000_000L))
                .andExpect(jsonPath("$[1].status").value("HELD"))
                .andExpect(jsonPath("$[1].currentHoldId").doesNotExist())
                .andExpect(jsonPath("$[1].userId").doesNotExist());
    }

    @Test
    @DisplayName("회차가 존재하지만 판매 좌석이 없으면 빈 배열을 반환한다")
    void returnsEmptyArrayForPerformanceWithoutSaleSeats() throws Exception {
        includeInLayout(layoutId, persistPhysicalSeat("1"), "A", "10.25");
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get(PATH, performanceId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("없는 회차는 빈 배열 대신 404 NOT_FOUND를 반환한다")
    void rejectsMissingPerformance() throws Exception {
        mockMvc.perform(get(PATH, -1).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("숫자로 변환할 수 없는 회차 ID는 400 INVALID_REQUEST를 반환한다")
    void rejectsNonNumericPerformanceId() throws Exception {
        mockMvc.perform(get(PATH, "invalid").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("로그인하지 않으면 좌석 조회는 401 UNAUTHORIZED를 반환한다")
    void rejectsUnauthenticatedSeatQuery() throws Exception {
        mockMvc.perform(get(PATH, performanceId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Long persistPerformance(Long targetLayoutId) {
        Performance performance = Performance.create(concertId, venueId, targetLayoutId,
                NOW.plusSeconds(14400), NOW.plusSeconds(3600), NOW.plusSeconds(10800), 4, "SCHEDULED");
        entityManager.persist(performance);
        return performance.getId();
    }

    private Long persistPhysicalSeat(String number) {
        VenueSeat seat = VenueSeat.create(venueId, sectionId, "1", number, false, "ACTIVE");
        entityManager.persist(seat);
        return seat.getId();
    }

    private void includeInLayout(Long targetLayoutId, Long physicalId, String label, String x) {
        VenueSeat physical = entityManager.find(VenueSeat.class, physicalId);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(targetLayoutId, physicalId), venueId,
                label, "1", physical.getSeatNumber(), new BigDecimal(x), new BigDecimal("-5.25"), true));
    }

    private TicketSeat persistTicketSeat(Long targetPerformanceId, Long targetLayoutId, Long physicalId,
                                        String grade, long price) {
        TicketSeat seat = TicketSeat.createAvailable(targetPerformanceId, targetLayoutId, physicalId, grade, price);
        entityManager.persist(seat);
        return seat;
    }
}
