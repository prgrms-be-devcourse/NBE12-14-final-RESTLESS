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

    boolean existsByPerformanceId(Long performanceId);

    Optional<TicketReservation> findByIdAndUserId(Long id, Long userId);

    Optional<TicketReservation> findByOrderId(Long orderId);

    List<TicketReservation> findAllByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long userId, Collection<TicketReservationStatus> statuses);

    Optional<TicketReservation> findFirstByUserIdAndPerformanceIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long userId, Long performanceId, Collection<TicketReservationStatus> statuses);

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

    /** 만료 후보 한 쪽의 크기. 쪽이 가득 차면 스케줄러가 다음 쪽을 이어 읽는다. */
    int EXPIRATION_PAGE_SIZE = 100;

    @Query(value = """
            SELECT id FROM ticket_reservations
            WHERE status = 'HELD' AND hold_expires_at <= UTC_TIMESTAMP(6)
            ORDER BY hold_expires_at, id LIMIT\s""" + EXPIRATION_PAGE_SIZE, nativeQuery = true)
    List<Long> findExpiredHoldIds();

    /**
     * (hold_expires_at, id) 순서에서 afterId 예약 다음 후보부터 읽는다.
     * 처리에 실패해 그대로 남은 앞쪽 후보가 뒤쪽 후보를 가리지 않게 한다.
     * hold_expires_at은 생성 후 바뀌지 않으므로 기준 예약의 값을 커서로 쓴다.
     */
    @Query(value = """
            SELECT r.id FROM ticket_reservations r
            JOIN ticket_reservations c ON c.id = :afterId
            WHERE r.status = 'HELD' AND r.hold_expires_at <= UTC_TIMESTAMP(6)
                AND r.hold_expires_at >= c.hold_expires_at
                AND (r.hold_expires_at > c.hold_expires_at OR r.id > c.id)
            ORDER BY r.hold_expires_at, r.id LIMIT\s""" + EXPIRATION_PAGE_SIZE, nativeQuery = true)
    List<Long> findExpiredHoldIdsAfter(@Param("afterId") Long afterId);

    @Query(value = """
            SELECT id FROM ticket_reservations
            WHERE status = 'PAYMENT_PENDING' AND payment_result_pending = FALSE
                AND order_id IS NOT NULL AND checkout_started_at IS NOT NULL
                AND payment_expires_at <= UTC_TIMESTAMP(6)
            ORDER BY payment_expires_at, id LIMIT\s""" + EXPIRATION_PAGE_SIZE, nativeQuery = true)
    List<Long> findExpiredPaymentIds();

    /** findExpiredHoldIdsAfter와 같은 커서 규칙. payment_expires_at은 checkout에서 한 번만 정해진다. */
    @Query(value = """
            SELECT r.id FROM ticket_reservations r
            JOIN ticket_reservations c ON c.id = :afterId
            WHERE r.status = 'PAYMENT_PENDING' AND r.payment_result_pending = FALSE
                AND r.order_id IS NOT NULL AND r.checkout_started_at IS NOT NULL
                AND r.payment_expires_at <= UTC_TIMESTAMP(6)
                AND r.payment_expires_at >= c.payment_expires_at
                AND (r.payment_expires_at > c.payment_expires_at OR r.id > c.id)
            ORDER BY r.payment_expires_at, r.id LIMIT\s""" + EXPIRATION_PAGE_SIZE, nativeQuery = true)
    List<Long> findExpiredPaymentIdsAfter(@Param("afterId") Long afterId);

    interface ReservationOwner {
        Long getUserId();
        Long getPerformanceId();
    }
}
