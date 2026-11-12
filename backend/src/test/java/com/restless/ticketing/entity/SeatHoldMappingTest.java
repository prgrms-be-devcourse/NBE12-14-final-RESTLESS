package com.restless.ticketing.entity;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.*;
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("좌석별 선점 이력 매핑")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@Transactional
class SeatHoldMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:02:03.123456Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(300);

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long venueId;
    private Long sectionId;
    private Long layoutId;
    private Long concertId;
    private Long performanceId;
    private Long reservationId;
    private Long seatId;

    @BeforeEach
    void setUp() {
        Member member = Member.register("hold-mapping@example.com", "test-hash", "User", NOW);
        entityManager.persist(member);
        userId = member.getId();
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", NOW);
        entityManager.persist(venue);
        venueId = venue.getId();
        VenueSection section = VenueSection.create(venueId, "A", "Section A", "1F", "ACTIVE");
        entityManager.persist(section);
        sectionId = section.getId();
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", 1, "Basic", "PUBLISHED", NOW);
        entityManager.persist(layout);
        layoutId = layout.getId();
        Concert concert = Concert.create(venueId, userId, "RESTLESS Concert", "SCHEDULED", NOW);
        entityManager.persist(concert);
        concertId = concert.getId();
        performanceId = persistPerformance(0);
        reservationId = persistReservation(performanceId, "first");
        seatId = persistSeat(performanceId, "1");
    }

    @DisplayName("좌석별 선점 스냅샷을 조회하고 해제 이력과 새 소유자 연결을 보존한다")
    @Test
    void reloadsSeatHoldSnapshotsAndPreservesHistory() {
        Long secondSeatId = persistSeat(performanceId, "2");
        SeatHold first = persistHold(reservationId, performanceId, seatId, 3_000_000_000L);
        SeatHold second = persistHold(reservationId, performanceId, secondSeatId, 0L);
        entityManager.flush();
        entityManager.clear();

        SeatHold loaded = entityManager.find(SeatHold.class, first.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getReservationId()).isEqualTo(reservationId);
        assertThat(loaded.getPerformanceId()).isEqualTo(performanceId);
        assertThat(loaded.getPerformanceSeatId()).isEqualTo(seatId);
        assertThat(loaded.getPriceAmount()).isEqualTo(3_000_000_000L);
        assertThat(loaded.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(loaded.getStatus()).isEqualTo(SeatHoldStatus.HELD);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getReleasedAt()).isNull();
        assertThat(entityManager.find(SeatHold.class, second.getId()).getPriceAmount()).isZero();
        var holds = entityManager.createQuery("""
                select h from SeatHold h where h.reservationId = :reservationId order by h.id
                """, SeatHold.class).setParameter("reservationId", reservationId).getResultList();
        assertThat(holds).extracting(SeatHold::getId).containsExactly(first.getId(), second.getId());

        setHistoryFixture(first.getId(), SeatHoldStatus.RELEASED);
        setHistoryFixture(second.getId(), SeatHoldStatus.CONFIRMED);
        entityManager.createNativeQuery("UPDATE ticket_reservations SET status = 'RELEASED' WHERE id = :id")
                .setParameter("id", reservationId).executeUpdate();
        Long newReservationId = persistReservation(performanceId, "replacement");
        SeatHold replacement = persistHold(newReservationId, performanceId, seatId, 100000);
        entityManager.flush();
        setOwnerFixture(replacement.getId());
        entityManager.clear();
        SeatHold released = entityManager.find(SeatHold.class, first.getId());
        assertThat(released.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        assertThat(released.getReleasedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(released.getPriceAmount()).isEqualTo(3_000_000_000L);
        assertThat(entityManager.find(SeatHold.class, second.getId()).getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED);
        assertThat(entityManager.find(SeatHold.class, second.getId()).getReleasedAt()).isNull();
        TicketSeat currentSeat = entityManager.find(TicketSeat.class, seatId);
        assertThat(currentSeat.getStatus()).isEqualTo(TicketSeatStatus.HELD);
        assertThat(currentSeat.getCurrentHoldId()).isEqualTo(replacement.getId());
        assertThat(entityManager.find(SeatHold.class, currentSeat.getCurrentHoldId()).getPerformanceSeatId())
                .isEqualTo(seatId);
        assertThat(entityManager.find(SeatHold.class, replacement.getId()).getReservationId()).isEqualTo(newReservationId);
    }

    @DisplayName("선점 기록에 다른 회차의 예약을 연결할 수 없다")
    @Test
    void rejectsReservationFromAnotherPerformance() {
        Long otherPerformanceId = persistPerformance(86400);
        Long otherReservationId = persistReservation(otherPerformanceId, "other-performance");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistHold(otherReservationId, performanceId, seatId, 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_seat_holds_reservation_performance");
    }

    @DisplayName("선점 기록에 다른 회차의 판매 좌석을 연결할 수 없다")
    @Test
    void rejectsSeatFromAnotherPerformance() {
        Long otherPerformanceId = persistPerformance(86400);
        Long otherSeatId = persistSeat(otherPerformanceId, "2");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistHold(reservationId, performanceId, otherSeatId, 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_seat_holds_seat_performance");
    }

    @DisplayName("선점 당시 가격 스냅샷은 음수로 저장할 수 없다")
    @Test
    void rejectsNegativePriceSnapshot() {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistHold(reservationId, performanceId, seatId, -1);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_seat_holds_price_amount");
    }

    private Long persistPerformance(long offset) {
        Performance performance = Performance.create(concertId, venueId, layoutId,
                NOW.plusSeconds(14400 + offset), NOW.plusSeconds(3600), NOW.plusSeconds(10800 + offset),
                4, "SCHEDULED");
        entityManager.persist(performance);
        return performance.getId();
    }

    private Long persistReservation(Long targetPerformanceId, String key) {
        TicketReservation reservation = TicketReservation.createHeld(userId, targetPerformanceId, key,
                new byte[32], EXPIRES_AT, NOW);
        entityManager.persist(reservation);
        return reservation.getId();
    }

    private Long persistSeat(Long targetPerformanceId, String number) {
        VenueSeat physical = VenueSeat.create(venueId, sectionId, "1", number, false, "ACTIVE");
        entityManager.persist(physical);
        entityManager.persist(LayoutSeat.create(new LayoutSeatId(layoutId, physical.getId()), venueId,
                "A", "1", number, BigDecimal.ZERO, BigDecimal.ZERO, true));
        TicketSeat seat = TicketSeat.createAvailable(targetPerformanceId, layoutId, physical.getId(), "VIP", 100000);
        entityManager.persist(seat);
        return seat.getId();
    }

    private SeatHold persistHold(Long targetReservationId, Long targetPerformanceId, Long targetSeatId, long price) {
        SeatHold hold = SeatHold.createHeld(targetReservationId, targetPerformanceId, targetSeatId,
                price, EXPIRES_AT, NOW);
        entityManager.persist(hold);
        return hold;
    }

    // 이력 상태·현재 포인터는 읽기 검증 fixture다. 선점·반환 업무 메서드는 후속 구현한다.
    private void setHistoryFixture(Long holdId, SeatHoldStatus status) {
        String releasedAt = status == SeatHoldStatus.RELEASED ? "'2026-11-12 01:03:03.123456'" : "NULL";
        entityManager.createNativeQuery("UPDATE seat_holds SET status = :status, released_at = " + releasedAt
                + " WHERE id = :id").setParameter("status", status.name()).setParameter("id", holdId).executeUpdate();
    }

    private void setOwnerFixture(Long holdId) {
        entityManager.createNativeQuery("""
                UPDATE performance_seats SET status = 'HELD', current_hold_id = :holdId WHERE id = :id
                """).setParameter("holdId", holdId).setParameter("id", seatId).executeUpdate();
    }
}
