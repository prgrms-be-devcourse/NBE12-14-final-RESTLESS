package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 팬미팅 주문(결제 기회) Repository. */
public interface FanMeetingOrderRepository extends JpaRepository<FanMeetingOrder, Long> {

    /** 탈퇴를 막을 주문이 있는지: 결제 대기·보류·환불 대기, 또는 결제 완료인데 팬미팅 전. */
    @Query("""
            select count(o) > 0 from FanMeetingOrder o, FanMeetingEvent e
             where e.id = o.eventId and o.userId = :userId
               and (o.status in (com.restless.fanmeeting.domain.FanMeetingOrderStatus.PENDING_PAYMENT,
                                 com.restless.fanmeeting.domain.FanMeetingOrderStatus.PAYMENT_REVIEW,
                                 com.restless.fanmeeting.domain.FanMeetingOrderStatus.REFUND_PENDING)
                    or (o.status = com.restless.fanmeeting.domain.FanMeetingOrderStatus.PAID and e.meetingAt > :now))
            """)
    boolean existsActiveParticipation(@Param("userId") Long userId, @Param("now") Instant now);

    /** 잠그기 전에 행사를 찾는다(일반 조회, 엔티티를 읽지 않음). event_id는 바뀌지 않는다. */
    @Query("select o.eventId from FanMeetingOrder o where o.id = :id")
    Optional<Long> findEventIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from FanMeetingOrder o where o.id = :id")
    Optional<FanMeetingOrder> findByIdForUpdate(@Param("id") Long id);

    /** 내 주문(잠금 없음). 회원 1명은 행사 1개에 주문이 최대 1개다. */
    Optional<FanMeetingOrder> findByEventIdAndUserId(Long eventId, Long userId);

    /** 선발의 주문(잠금 없음). */
    Optional<FanMeetingOrder> findBySelectionId(Long selectionId);

    /**
     * 만료 후보: 결제 대기, 기한 경과, 진행 중인 시도 없음. 잠금 없는 조회이고, 만료할 때 잠근 뒤 다시 확인한다.
     * issuedBefore는 {@link FanMeetingOrder#issuedAtPastDeadline}으로 구한다.
     */
    @Query("""
            select o.id as orderId, o.issuedAt as position from FanMeetingOrder o
             where o.status = com.restless.fanmeeting.domain.FanMeetingOrderStatus.PENDING_PAYMENT
               and o.issuedAt <= :issuedBefore and o.paymentId is null
               and (:afterTime is null or o.issuedAt > :afterTime
                    or (o.issuedAt = :afterTime and o.id > :afterId))
             order by o.issuedAt, o.id
            """)
    List<ExpiryCandidate> findIdsToExpire(@Param("issuedBefore") Instant issuedBefore,
            @Param("afterTime") Instant afterTime,
            @Param("afterId") Long afterId, Pageable limit);

    /**
     * 보류 경고 후보: 보류가 오래됐고 아직 경고하지 않음. 잠금 없는 조회이고, 잠근 뒤 다시 확인한다.
     * startedBefore는 {@link FanMeetingOrder#reviewStartedAtToWarn}으로 구한다.
     */
    @Query("""
            select o.id as orderId, o.reviewStartedAt as position from FanMeetingOrder o
             where o.status = com.restless.fanmeeting.domain.FanMeetingOrderStatus.PAYMENT_REVIEW
               and o.reviewStartedAt <= :startedBefore and o.reviewWarnedAt is null
               and (:afterTime is null or o.reviewStartedAt > :afterTime
                    or (o.reviewStartedAt = :afterTime and o.id > :afterId))
             order by o.reviewStartedAt, o.id
            """)
    List<ExpiryCandidate> findIdsToWarnReview(@Param("startedBefore") Instant startedBefore,
            @Param("afterTime") Instant afterTime,
            @Param("afterId") Long afterId, Pageable limit);

    interface ExpiryCandidate {
        Long getOrderId();

        Instant getPosition();
    }

    long countByEventIdAndStatus(Long eventId, FanMeetingOrderStatus status);

    /** 상태별 주문 수. */
    interface StatusCount {
        FanMeetingOrderStatus getStatus();

        long getCount();
    }

    @Query("""
            select o.status as status, count(o) as count from FanMeetingOrder o
             where o.eventId = :eventId group by o.status
            """)
    List<StatusCount> countByStatus(@Param("eventId") Long eventId);

    List<FanMeetingOrder> findByEventIdAndStatusOrderById(Long eventId, FanMeetingOrderStatus status);
}
