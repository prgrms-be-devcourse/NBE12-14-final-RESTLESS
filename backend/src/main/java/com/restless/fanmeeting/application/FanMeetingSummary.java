package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자 summary: 행사 진행 상황. occupied는 확정 + 결제 대기(INV-FM-07의 왼쪽)다.
 * maxPromotedReserveRank는 승격된 예비가 없으면 null이다. 회원 정보는 담지 않는다.
 */
public record FanMeetingSummary(FanMeetingEvent event, long applicationCount, long occupied,
                                Map<FanMeetingSelectionStatus, Long> selectionCounts,
                                Map<FanMeetingOrderStatus, Long> orderCounts, Integer maxPromotedReserveRank,
                                List<Review> reviews, List<Long> refundPendingOrderIds) {

    /** 결제 결과를 모르는 보류 주문 1건과 보류가 이어진 시간(분, 내림). */
    public record Review(Long orderId, Instant reviewStartedAt, long elapsedMinutes) {
    }
}
