package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.Ticket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findAllByReservationIdOrderByPerformanceSeatIdAsc(Long reservationId);
}
