package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.application.MyFanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import java.time.Instant;

/**
 * 내 주문. declinable은 지금 포기 버튼을 보여 줘도 되는지(결제 대기, 결제 준비 요청 없음, 기한 전).
 * 참가비가 0원이면 결제 대신 확정 API를 부른다.
 */
public record MyOrderResponse(Long orderId, String status, long amount, Instant issuedAt, Instant paymentDeadline,
                              Instant paidAt, boolean declinable) {

    public static MyOrderResponse from(MyFanMeetingOrder mine) {
        FanMeetingOrder order = mine.order();
        return new MyOrderResponse(order.getId(), order.getStatus().name(), order.getAmount(), order.getIssuedAt(),
                order.paymentDeadline(), order.getPaidAt(), mine.declinable());
    }
}
