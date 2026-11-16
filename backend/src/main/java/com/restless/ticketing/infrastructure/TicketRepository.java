package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.Ticket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    String OWNED_TICKETS = """
            select new com.restless.ticketing.infrastructure.IssuedTicketRow(
                t.id, t.ticketNo, r.id, r.orderId, t.performanceId, s.id, s.venueSeatId, s.layoutId, s.grade,
                t.priceAmount, t.status, t.issuedAt, t.usedAt, t.cancelledAt)
            from Ticket t join TicketReservation r on r.id = t.reservationId
                join TicketSeat s on s.id = t.performanceSeatId
            where r.userId = :userId
            """;

    @Query(value = OWNED_TICKETS + " order by t.issuedAt desc, t.id desc",
            countQuery = "select count(t) from Ticket t join TicketReservation r on r.id=t.reservationId where r.userId=:userId")
    Page<IssuedTicketRow> findOwnedTickets(@Param("userId") Long userId, Pageable pageable);

    @Query(OWNED_TICKETS + " and t.id = :ticketId")
    Optional<IssuedTicketRow> findOwnedTicket(@Param("userId") Long userId, @Param("ticketId") Long ticketId);

    List<Ticket> findAllByReservationIdOrderByPerformanceSeatIdAsc(Long reservationId);
}
