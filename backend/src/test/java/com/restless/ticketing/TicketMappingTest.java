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
import com.restless.ticketing.domain.Ticket;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
class TicketMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:02:03.123456Z");

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
        Member member = Member.register("ticket-mapping@example.com", "test-hash", "User", NOW);
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

    @Test
    void reloadsTicketsWithDistinctUuidV4NumbersForMultipleSeats() {
        Long secondSeatId = persistSeat(performanceId, "2");
        Ticket first = persistTicket(reservationId, performanceId, seatId, 3_000_000_000L);
        Ticket second = persistTicket(reservationId, performanceId, secondSeatId, 0);
        String firstNumber = first.getTicketNo();
        entityManager.flush();
        entityManager.clear();

        Ticket loaded = entityManager.find(Ticket.class, first.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getReservationId()).isEqualTo(reservationId);
        assertThat(loaded.getPerformanceId()).isEqualTo(performanceId);
        assertThat(loaded.getPerformanceSeatId()).isEqualTo(seatId);
        assertThat(loaded.getTicketNo()).isEqualTo(firstNumber);
        assertThat(loaded.getPriceAmount()).isEqualTo(3_000_000_000L);
        assertThat(loaded.getStatus()).isEqualTo("ISSUED");
        assertThat(loaded.getIssuedAt()).isEqualTo(NOW);
        assertThat(loaded.getUsedAt()).isNull();
        assertThat(loaded.getCancelledAt()).isNull();
        UUID number = UUID.fromString(loaded.getTicketNo());
        assertThat(number.version()).isEqualTo(4);
        assertThat(number.variant()).isEqualTo(2);
        assertThat(loaded.getTicketNo()).hasSize(36).isEqualTo(number.toString());

        Ticket other = entityManager.find(Ticket.class, second.getId());
        assertThat(other.getTicketNo()).isNotEqualTo(firstNumber);
        assertThat(UUID.fromString(other.getTicketNo()).version()).isEqualTo(4);
        assertThat(other.getPriceAmount()).isZero();
        var tickets = entityManager.createQuery("""
                select t from Ticket t where t.reservationId = :reservationId order by t.id
                """, Ticket.class).setParameter("reservationId", reservationId).getResultList();
        assertThat(tickets).extracting(Ticket::getId).containsExactly(first.getId(), second.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateReservationSeatEvenAfterCancellation(boolean cancelled) {
        Ticket first = persistTicket(reservationId, performanceId, seatId, 100000);
        entityManager.flush();
        if (cancelled) setHistoryFixture(first.getId(), "CANCELLED");

        assertThatThrownBy(() -> {
            persistTicket(reservationId, performanceId, seatId, 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_tickets_reservation_seat");
    }

    @Test
    void rejectsDuplicateTicketNumberAcrossDifferentSeats() {
        Long secondSeatId = persistSeat(performanceId, "2");
        Ticket first = persistTicket(reservationId, performanceId, seatId, 100000);
        Ticket second = persistTicket(reservationId, performanceId, secondSeatId, 100000);
        entityManager.flush();

        // UUID 충돌을 의도적으로 재현해 번호 UNIQUE와 발급 대상 UNIQUE가 독립적으로 동작하는지 확인한다.
        assertThatThrownBy(() -> entityManager.createNativeQuery("UPDATE tickets SET ticket_no = :number WHERE id = :id")
                .setParameter("number", first.getTicketNo()).setParameter("id", second.getId()).executeUpdate())
                .isInstanceOf(PersistenceException.class).hasStackTraceContaining("uk_tickets_ticket_no");
    }

    @Test
    void rejectsReservationFromDifferentPerformance() {
        Long otherPerformanceId = persistPerformance(86400);
        Long otherReservationId = persistReservation(otherPerformanceId, "other-performance");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicket(otherReservationId, performanceId, seatId, 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining("fk_tickets_reservation_performance");
    }

    @Test
    void rejectsSeatFromDifferentPerformance() {
        Long otherPerformanceId = persistPerformance(86400);
        Long otherSeatId = persistSeat(otherPerformanceId, "2");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicket(reservationId, performanceId, otherSeatId, 100000);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining("fk_tickets_seat_performance");
    }

    @Test
    void rejectsNegativeTicketPrice() {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistTicket(reservationId, performanceId, seatId, -1);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining("ck_tickets_price_amount");
    }

    @ParameterizedTest
    @CsvSource({"USED,true,false", "CANCELLED,false,true"})
    void readsStoredUsageAndCancellationHistory(String status, boolean used, boolean cancelled) {
        Ticket ticket = persistTicket(reservationId, performanceId, seatId, 100000);
        String number = ticket.getTicketNo();
        entityManager.flush();
        setHistoryFixture(ticket.getId(), status);
        entityManager.clear();

        Ticket loaded = entityManager.find(Ticket.class, ticket.getId());
        assertThat(loaded.getStatus()).isEqualTo(status);
        assertThat(loaded.getUsedAt()).isEqualTo(used ? NOW.plusSeconds(60) : null);
        assertThat(loaded.getCancelledAt()).isEqualTo(cancelled ? NOW.plusSeconds(60) : null);
        assertThat(loaded.getTicketNo()).isEqualTo(number);
    }

    @Test
    void preservesCancelledTicketAndIssuesNewNumberForNewReservation() {
        Ticket oldTicket = persistTicket(reservationId, performanceId, seatId, 100000);
        String oldNumber = oldTicket.getTicketNo();
        entityManager.flush();
        setHistoryFixture(oldTicket.getId(), "CANCELLED");
        Long newReservationId = persistReservation(performanceId, "resale");
        Ticket newTicket = persistTicket(newReservationId, performanceId, seatId, 100000);
        entityManager.flush();
        entityManager.clear();

        Ticket cancelled = entityManager.find(Ticket.class, oldTicket.getId());
        Ticket issued = entityManager.find(Ticket.class, newTicket.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(cancelled.getTicketNo()).isEqualTo(oldNumber);
        assertThat(issued.getReservationId()).isEqualTo(newReservationId);
        assertThat(issued.getTicketNo()).isNotEqualTo(oldNumber);
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
                new byte[32], NOW.plusSeconds(300), NOW);
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

    private Ticket persistTicket(Long targetReservationId, Long targetPerformanceId, Long targetSeatId, long price) {
        Ticket ticket = Ticket.issue(targetReservationId, targetPerformanceId, targetSeatId, price, "ISSUED", NOW);
        entityManager.persist(ticket);
        return ticket;
    }

    // 상태값은 읽기 검증 예시다. 실제 사용·환불·재판매 업무와 전이 정책은 후속 구현한다.
    private void setHistoryFixture(Long ticketId, String status) {
        entityManager.createNativeQuery("""
                UPDATE tickets SET status = :status,
                    used_at = CASE WHEN :status = 'USED' THEN '2026-11-12 01:03:03.123456' ELSE NULL END,
                    cancelled_at = CASE WHEN :status = 'CANCELLED' THEN '2026-11-12 01:03:03.123456' ELSE NULL END
                WHERE id = :id
                """).setParameter("status", status).setParameter("id", ticketId).executeUpdate();
    }
}
