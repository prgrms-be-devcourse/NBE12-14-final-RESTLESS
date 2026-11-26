package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.application.dto.TicketSeatState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface TicketSeatRepository extends JpaRepository<TicketSeat, Long> {

    List<TicketSeat> findAllByPerformanceId(Long performanceId);

    List<TicketSeat> findAllByPerformanceIdIn(Collection<Long> performanceIds);

    @Query("select new com.restless.ticketing.application.dto.TicketSeatState(s.id, s.status) "
            + "from TicketSeat s where s.performanceId = :performanceId order by s.id")
    List<TicketSeatState> findStatesByPerformanceId(@Param("performanceId") Long performanceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TicketSeat s where s.id = :id")
    Optional<TicketSeat> findLockedById(@Param("id") Long id);
}
