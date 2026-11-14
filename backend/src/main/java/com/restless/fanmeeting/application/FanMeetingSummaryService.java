package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingApplicationStatus;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 summary 조회. 잠그지 않고 읽는다(한 트랜잭션의 같은 스냅샷). */
@Service
@RequiredArgsConstructor
public class FanMeetingSummaryService {

    private final FanMeetingEventRepository events;
    private final FanMeetingApplicationRepository applications;
    private final FanMeetingSelectionRepository selections;
    private final FanMeetingOrderRepository orders;
    private final Clock clock;

    /** 없는 행사는 FAN_MEETING_NOT_FOUND. 상태별 인원은 0명인 상태도 모두 담는다. */
    @Transactional(readOnly = true)
    public FanMeetingSummary summary(Long eventId) {
        FanMeetingEvent event = events.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
        Instant now = clock.instant();

        Map<FanMeetingSelectionStatus, Long> selectionCounts = new EnumMap<>(FanMeetingSelectionStatus.class);
        for (FanMeetingSelectionStatus status : FanMeetingSelectionStatus.values()) {
            selectionCounts.put(status, 0L);
        }
        selections.countByStatus(eventId).forEach(row -> selectionCounts.put(row.getStatus(), row.getCount()));

        Map<FanMeetingOrderStatus, Long> orderCounts = new EnumMap<>(FanMeetingOrderStatus.class);
        for (FanMeetingOrderStatus status : FanMeetingOrderStatus.values()) {
            orderCounts.put(status, 0L);
        }
        orders.countByStatus(eventId).forEach(row -> orderCounts.put(row.getStatus(), row.getCount()));

        List<FanMeetingSummary.Review> reviews = orders
                .findByEventIdAndStatusOrderById(eventId, FanMeetingOrderStatus.PAYMENT_REVIEW).stream()
                .map(order -> new FanMeetingSummary.Review(order.getId(), order.getReviewStartedAt(),
                        Duration.between(order.getReviewStartedAt(), now).toMinutes()))
                .toList();
        List<Long> refundPending = orders
                .findByEventIdAndStatusOrderById(eventId, FanMeetingOrderStatus.REFUND_PENDING).stream()
                .map(FanMeetingOrder::getId)
                .toList();

        long occupied = selectionCounts.get(FanMeetingSelectionStatus.CONFIRMED)
                + selectionCounts.get(FanMeetingSelectionStatus.PAYMENT_PENDING);
        return new FanMeetingSummary(event,
                applications.countByEventIdAndStatus(eventId, FanMeetingApplicationStatus.APPLIED), occupied,
                selectionCounts, orderCounts, selections.findMaxPromotedReserveRank(eventId), reviews, refundPending);
    }
}
