package com.restless.order.api;

import com.restless.auth.LoginMember;
import com.restless.order.application.OrderDetail;
import com.restless.order.application.OrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 상세 (common.md 주문 상세). 목록은 {@link OrderController}가 {@code /api/me/orders}로 준다. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderDetailController {
    private final OrderQueryService orderQueryService;

    @GetMapping("/{orderId}")
    public OrderDetail getDetail(@LoginMember Long userId, @PathVariable Long orderId) {
        return orderQueryService.getDetail(userId, orderId);
    }
}
