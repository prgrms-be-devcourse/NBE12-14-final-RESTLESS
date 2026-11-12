package com.restless.ticketing;

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
import com.restless.ticketing.domain.TicketSeatStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketSeatMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:02:03.123456Z");

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long venueId;
    private Long sectionId;
    private Long concertId;
    private Long layoutId;
    private Long venueSeatId;
    private Long performanceId;

    @BeforeEach
    void setUp() {
        Member user = Member.register("ticket-seat-mapping@example.com", "test-hash", "Host", NOW);
        entityManager.persist(user);
        userId = user.getId();
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", NOW);
        entityManager.persist(venue);
        venueId = venue.getId();
        VenueSection section = VenueSection.create(venueId, "A", "Section A", "1F", "ACTIVE");
        entityManager.persist(section);
        sectionId = section.getId();
        venueSeatId = persistPhysicalSeat("1");
        layoutId = persistLayout(1);
        includeInLayout(layoutId, venueSeatId);
        Concert concert = Concert.create(venueId, userId, "RESTLESS Concert", "SCHEDULED", NOW);
        entityManager.persist(concert);
        concertId = concert.getId();
        performanceId = persistPerformance(0);
    }

    @Test
    void reloadsSeparatePricesAndStatesForSamePhysicalSeatAcrossPerformances() {
        Long otherPerformanceId = persistPerformance(86400);
        TicketSeat first = persistTicketSeat(performanceId, venueSeatId, "VIP", 3_000_000_000L);
        TicketSeat second = persistTicketSeat(otherPerformanceId, venueSeatId, "R", 0L);
        entityManager.flush();
        entityManager.clear();

        TicketSeat loaded = entityManager.find(TicketSeat.class, first.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getPerformanceId()).isEqualTo(performanceId);
        assertThat(loaded.getLayoutId()).isEqualTo(layoutId);
        assertThat(loaded.getVenueSeatId()).isEqualTo(venueSeatId);
        assertThat(loaded.getGrade()).isEqualTo("VIP");
        assertThat(loaded.getPrice()).isEqualTo(3_000_000_000L);
        assertThat(loaded.getStatus()).isEqualTo(TicketSeatStatus.AVAILABLE);
        assertThat(loaded.getCurrentHoldId()).isNull();

        TicketSeat other = entityManager.find(TicketSeat.class, second.getId());
        assertThat(other.getPerformanceId()).isEqualTo(otherPerformanceId);
        assertThat(other.getVenueSeatId()).isEqualTo(venueSeatId);
        assertThat(other.getGrade()).isEqualTo("R");
        assertThat(other.getPrice()).isZero();
        assertThat(other.getStatus()).isEqualTo(TicketSeatStatus.AVAILABLE);
        var seats = entityManager.createQuery("""
                select s from TicketSeat s where s.performanceId = :performanceId
                """, TicketSeat.class).setParameter("performanceId", performanceId).getResultList();
        assertThat(seats).extracting(TicketSeat::getId).containsExactly(first.getId());
    }

    @Test
    void rejectsDuplicatePhysicalSeatWithinPerformance() {
        persistTicketSeat(performanceId, venueSeatId, "VIP", 100000);
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicketSeat(performanceId, venueSeatId, "R", 90000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_performance_seats_seat");
    }

    @Test
    void rejectsLayoutDifferentFromSelectedPerformanceLayout() {
        Long otherLayoutId = persistLayout(2);
        includeInLayout(otherLayoutId, venueSeatId);
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(TicketSeat.createAvailable(performanceId, otherLayoutId, venueSeatId,
                    "VIP", 100000));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_performance_seats_performance_layout");
    }

    @Test
    void rejectsPhysicalSeatNotIncludedInLayout() {
        Long missingSeatId = persistPhysicalSeat("2");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicketSeat(performanceId, missingSeatId, "VIP", 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_performance_seats_layout_seat");
    }

    @Test
    void rejectsNegativePrice() {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicketSeat(performanceId, venueSeatId, "VIP", -1);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_performance_seats_price");
    }

    @Test
    void reloadsHeldAndSoldWithSameCurrentHoldPointer() {
        TicketSeat seat = persistTicketSeat(performanceId, venueSeatId, "VIP", 100000);
        entityManager.flush();
        Long holdId = createHoldFixture(seat.getId());
        setOwnerFixture(seat.getId(), "HELD", holdId);
        entityManager.clear();

        TicketSeat held = entityManager.find(TicketSeat.class, seat.getId());
        assertThat(held.getStatus()).isEqualTo(TicketSeatStatus.HELD);
        assertThat(held.getCurrentHoldId()).isEqualTo(holdId);

        setOwnerFixture(seat.getId(), "SOLD", holdId);
        entityManager.clear();
        TicketSeat sold = entityManager.find(TicketSeat.class, seat.getId());
        assertThat(sold.getStatus()).isEqualTo(TicketSeatStatus.SOLD);
        assertThat(sold.getCurrentHoldId()).isEqualTo(holdId);
    }

    @ParameterizedTest
    @CsvSource({"AVAILABLE,true", "HELD,false", "SOLD,false", "BROKEN,false"})
    void rejectsInvalidStatusOwnerCombination(String status, boolean withOwner) {
        TicketSeat seat = persistTicketSeat(performanceId, venueSeatId, "VIP", 100000);
        entityManager.flush();
        Long holdId = createHoldFixture(seat.getId());

        assertThatThrownBy(() -> setOwnerFixture(seat.getId(), status, withOwner ? holdId : null))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_performance_seats_status_owner");
    }

    @Test
    void rejectsCurrentHoldBelongingToAnotherSeat() {
        Long otherSeatId = persistPhysicalSeat("2");
        includeInLayout(layoutId, otherSeatId);
        TicketSeat first = persistTicketSeat(performanceId, venueSeatId, "VIP", 100000);
        TicketSeat second = persistTicketSeat(performanceId, otherSeatId, "VIP", 100000);
        entityManager.flush();
        Long holdId = createHoldFixture(first.getId());

        assertThatThrownBy(() -> setOwnerFixture(second.getId(), "HELD", holdId))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_performance_seats_current_hold_seat");
    }

    private Long persistPhysicalSeat(String number) {
        VenueSeat seat = VenueSeat.create(venueId, sectionId, "1", number, false, "ACTIVE");
        entityManager.persist(seat);
        return seat.getId();
    }

    private Long persistLayout(int version) {
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", version, "Basic", "PUBLISHED", NOW);
        entityManager.persist(layout);
        return layout.getId();
    }

    private void includeInLayout(Long targetLayoutId, Long seatId) {
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(targetLayoutId, seatId), venueId,
                "A", "1", "1", BigDecimal.ZERO, BigDecimal.ZERO, true));
    }

    private Long persistPerformance(long offset) {
        Performance performance = Performance.create(concertId, venueId, layoutId,
                NOW.plusSeconds(14400 + offset), NOW.plusSeconds(3600), NOW.plusSeconds(10800 + offset),
                4, "SCHEDULED");
        entityManager.persist(performance);
        return performance.getId();
    }

    private TicketSeat persistTicketSeat(Long targetPerformanceId, Long seatId, String grade, long price) {
        TicketSeat seat = TicketSeat.createAvailable(targetPerformanceId, layoutId, seatId, grade, price);
        entityManager.persist(seat);
        return seat;
    }

    private Long createHoldFixture(Long ticketSeatId) {
        TicketReservation reservation = TicketReservation.createHeld(userId, performanceId, "seat-mapping-hold",
                new byte[32], NOW.plusSeconds(300), NOW);
        entityManager.persist(reservation);
        SeatHold hold = SeatHold.createHeld(reservation.getId(), performanceId, ticketSeatId,
                100000, NOW.plusSeconds(300), NOW);
        entityManager.persist(hold);
        entityManager.flush();
        return hold.getId();
    }

    private void setOwnerFixture(Long seatId, String status, Long holdId) {
        entityManager.createNativeQuery("""
                UPDATE performance_seats SET status = :status, current_hold_id = :holdId WHERE id = :seatId
                """).setParameter("status", status).setParameter("holdId", holdId)
                .setParameter("seatId", seatId).executeUpdate();
    }
}
