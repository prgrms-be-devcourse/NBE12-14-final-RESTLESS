package com.restless.concert.infrastructure;

import com.restless.concert.domain.Performance;
import com.restless.concert.application.dto.TicketSeatDescription;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findAllByConcertIdOrderByStartsAtAsc(Long concertId);

    @Query("""
            select new com.restless.concert.application.dto.TicketSeatDescription(
                p.id, p.layoutId, c.id, c.title, p.startsAt, p.venueId, l.id.venueSeatId,
                l.sectionLabel, l.rowLabel, l.seatNumber)
            from Performance p join Concert c on c.id = p.concertId
                join LayoutSeat l on l.id.layoutId = p.layoutId
            where p.id in :performanceIds and l.id.venueSeatId in :venueSeatIds
            """)
    List<TicketSeatDescription> findTicketSeatDescriptions(@Param("performanceIds") Collection<Long> performanceIds,
                                                          @Param("venueSeatIds") Collection<Long> venueSeatIds);
}
