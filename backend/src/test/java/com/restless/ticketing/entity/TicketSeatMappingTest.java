package com.restless.ticketing.entity;

import com.restless.TestcontainersConfiguration;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import com.restless.concert.domain.*;
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.domain.TicketSeatStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("회차별 판매 좌석 매핑")
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

    @Autowired
    private TicketSeatRepository ticketSeatRepository;

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

    @DisplayName("같은 실제 좌석의 회차별 가격·등급과 현재 소유자 상태를 저장하고 조회한다")
    @Test
    void reloadsPerformancePricesAndCurrentOwnerStates() {
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
        var seats = ticketSeatRepository.findAllByPerformanceId(performanceId);
        assertThat(seats).extracting(TicketSeat::getId).containsExactly(first.getId());

        Long holdId = createHoldFixture(first.getId());
        setOwnerFixture(first.getId(), "HELD", holdId);
        entityManager.clear();
        TicketSeat held = entityManager.find(TicketSeat.class, first.getId());
        assertThat(held.getStatus()).isEqualTo(TicketSeatStatus.HELD);
        assertThat(held.getCurrentHoldId()).isEqualTo(holdId);
        setOwnerFixture(first.getId(), "SOLD", holdId);
        entityManager.clear();
        TicketSeat sold = entityManager.find(TicketSeat.class, first.getId());
        assertThat(sold.getStatus()).isEqualTo(TicketSeatStatus.SOLD);
        assertThat(sold.getCurrentHoldId()).isEqualTo(holdId);
    }

    @DisplayName("같은 회차에 같은 실제 좌석을 중복 등록할 수 없다")
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

    @DisplayName("회차가 선택한 배치와 다른 배치의 좌석을 판매 좌석으로 등록할 수 없다")
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

    @DisplayName("해당 배치에 포함되지 않은 실제 좌석을 판매 좌석으로 등록할 수 없다")
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

    @DisplayName("판매 좌석 가격은 음수로 저장할 수 없다")
    @Test
    void rejectsNegativePrice() {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicketSeat(performanceId, venueSeatId, "VIP", -1);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_performance_seats_price");
    }

    @DisplayName("판매 상태와 현재 선점 유무가 맞지 않거나 허용되지 않은 상태이면 거절한다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"AVAILABLE,true", "HELD,false", "SOLD,false", "BROKEN,false"})
    void rejectsInvalidStatusOwnerCombination(String status, boolean withOwner) {
        TicketSeat seat = persistTicketSeat(performanceId, venueSeatId, "VIP", 100000);
        entityManager.flush();
        Long holdId = createHoldFixture(seat.getId());

        assertThatThrownBy(() -> setOwnerFixture(seat.getId(), status, withOwner ? holdId : null))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_performance_seats_status_owner");
    }

    @DisplayName("다른 좌석의 선점 기록을 현재 소유자로 연결할 수 없다")
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
