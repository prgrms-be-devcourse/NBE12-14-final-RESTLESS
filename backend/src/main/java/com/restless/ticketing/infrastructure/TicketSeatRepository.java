package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.TicketSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketSeatRepository extends JpaRepository<TicketSeat, Long> {

    List<TicketSeat> findAllByPerformanceId(Long performanceId);
}
