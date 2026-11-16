package com.restless.fanmeeting.api;

import com.restless.auth.LoginMember;
import com.restless.fanmeeting.api.dto.MyOrderResponse;
import com.restless.fanmeeting.api.dto.MyResultResponse;
import com.restless.fanmeeting.application.FanMeetingOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 추첨 결과, 내 주문, 결제 포기, 0원 주문 확정. 회원 ID는 토큰에서만 받는다. 결제는 공통 결제 API를 쓴다. */
@RestController
@RequestMapping("/api/fan-meetings/{eventId}")
@RequiredArgsConstructor
public class FanMeetingOrderController {

    private final FanMeetingOrderService orderService;

    @GetMapping("/result/me")
    public MyResultResponse myResult(@LoginMember Long userId, @PathVariable Long eventId) {
        return MyResultResponse.from(orderService.findMyResult(userId, eventId));
    }

    @GetMapping("/orders/me")
    public MyOrderResponse myOrder(@LoginMember Long userId, @PathVariable Long eventId) {
        return MyOrderResponse.from(orderService.findMyOrder(userId, eventId));
    }

    @PostMapping("/orders/{orderId}/decline")
    public MyOrderResponse decline(@LoginMember Long userId, @PathVariable Long eventId,
                                   @PathVariable Long orderId) {
        return MyOrderResponse.from(orderService.decline(userId, eventId, orderId));
    }

    /** 참가비 0원 주문을 결제 없이 확정한다. */
    @PostMapping("/orders/{orderId}/confirm")
    public MyOrderResponse confirmFree(@LoginMember Long userId, @PathVariable Long eventId,
                                       @PathVariable Long orderId) {
        return MyOrderResponse.from(orderService.confirmFree(userId, eventId, orderId));
    }
}
