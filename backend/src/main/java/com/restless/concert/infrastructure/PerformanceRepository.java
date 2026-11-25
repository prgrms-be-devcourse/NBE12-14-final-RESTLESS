package com.restless.concert.infrastructure;

import com.restless.concert.domain.Performance;
import com.restless.concert.application.dto.TicketSeatDescription;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findAllByConcertIdOrderByStartsAtAsc(Long concertId);

    @Query("select p.maxTicketsPerUser from Performance p where p.id = :id")
    Optional<Integer> findMaxTicketsPerUser(@Param("id") Long id);

    @Query("select p.concertId from Performance p where p.id = :id")
    Optional<Long> findConcertId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Performance p where p.id = :id")
    Optional<Performance> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Performance p where p.id = :id")
    Optional<Performance> findSharedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Performance p where p.concertId = :concertId order by p.id")
    List<Performance> findAllLockedByConcertId(@Param("concertId") Long concertId);
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
