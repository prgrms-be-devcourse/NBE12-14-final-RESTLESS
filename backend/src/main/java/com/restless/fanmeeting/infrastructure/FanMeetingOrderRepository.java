package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingOrder;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
