package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketReservationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketReservationRepository extends JpaRepository<TicketReservation, Long> {

    Optional<TicketReservation> findByIdAndUserId(Long id, Long userId);

    List<TicketReservation> findAllByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long userId, Collection<TicketReservationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TicketReservation> findByUserIdAndRequestKey(Long userId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TicketReservation> findByUserIdAndPerformanceIdAndStatusIn(
            Long userId, Long performanceId, Collection<TicketReservationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TicketReservation r where r.id = :id")
    Optional<TicketReservation> findLockedById(@Param("id") Long id);

    @Query("select r.userId as userId, r.performanceId as performanceId from TicketReservation r where r.id = :id")
    Optional<ReservationOwner> findOwnerById(@Param("id") Long id);

    @Query("""
            select r.userId as userId, r.performanceId as performanceId from TicketReservation r
            where r.userId = :userId and r.requestKey = :requestKey
            """)
    Optional<ReservationOwner> findOwnerByUserIdAndRequestKey(@Param("userId") Long userId,
                                                           @Param("requestKey") String requestKey);

    @Query(value = """
            SELECT id FROM ticket_reservations
            WHERE status = 'HELD' AND hold_expires_at <= UTC_TIMESTAMP(6)
            ORDER BY hold_expires_at, id LIMIT 100
            """, nativeQuery = true)
    List<Long> findExpiredHoldIds();

    interface ReservationOwner {
        Long getUserId();
        Long getPerformanceId();
    }
}
