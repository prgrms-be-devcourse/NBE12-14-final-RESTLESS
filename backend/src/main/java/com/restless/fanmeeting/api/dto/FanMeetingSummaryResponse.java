package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.application.FanMeetingSummary;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자 summary. applicationCount는 취소하지 않은 응모 수, occupied는 확정 + 결제 대기다.
 * maxPromotedReserveRank는 승격된 예비가 없으면 null. 회원 ID는 내보내지 않는다.
 */
public record FanMeetingSummaryResponse(Long eventId, String status, int winnerLimit, int reserveLimit,
                                        Instant promotionClosesAt, long applicationCount, long occupied,
                                        Map<FanMeetingSelectionStatus, Long> selectionCounts,
                                        Map<FanMeetingOrderStatus, Long> orderCounts,
                                        Integer maxPromotedReserveRank, List<Review> reviews,
                                        List<RefundPending> refundPending) {

    public record Review(Long orderId, Instant reviewStartedAt, long elapsedMinutes) {
    }

    public record RefundPending(Long orderId) {
    }

    public static FanMeetingSummaryResponse from(FanMeetingSummary summary) {
        FanMeetingEvent event = summary.event();
        return new FanMeetingSummaryResponse(event.getId(), event.getStatus().name(), event.getWinnerLimit(),
                event.getReserveLimit(), event.getPromotionClosesAt(), summary.applicationCount(),
                summary.occupied(), summary.selectionCounts(), summary.orderCounts(),
                summary.maxPromotedReserveRank(),
                summary.reviews().stream()
                        .map(r -> new Review(r.orderId(), r.reviewStartedAt(), r.elapsedMinutes())).toList(),
                summary.refundPendingOrderIds().stream().map(RefundPending::new).toList());
    }
}
