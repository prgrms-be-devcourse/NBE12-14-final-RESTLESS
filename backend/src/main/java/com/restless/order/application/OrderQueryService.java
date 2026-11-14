package com.restless.order.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.order.domain.Order;
import com.restless.order.domain.OrderItem;
import com.restless.order.infrastructure.OrderItemRepository;
import com.restless.order.infrastructure.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderQueryService {
    private final MemberService members;
    private final OrderRepository orders;
    private final OrderItemRepository items;

    @Transactional(readOnly = true)
    public MyOrdersPage getList(Long userId, int page, int size) {
        members.getActive(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        var result = orders.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
        List<Long> orderIds = result.stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> lines = orderIds.isEmpty() ? Map.of()
                : items.findByOrderIdInOrderByIdAsc(orderIds).stream()
                    .collect(Collectors.groupingBy(OrderItem::getOrderId));
        var summaries = result.stream().map(order -> new MyOrdersPage.Summary(
                order.getId(), order.getOrderType(), order.getStatus(), order.getTotalAmount(), order.getCreatedAt(),
                lines.getOrDefault(order.getId(), List.of()).stream()
                    .map(item -> new MyOrdersPage.Line(item.getItemType(), item.getItemName(), item.getOptionName(),
                            item.getUnitPrice(), item.getQuantity()))
                    .toList())).toList();
        return new MyOrdersPage(summaries, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
