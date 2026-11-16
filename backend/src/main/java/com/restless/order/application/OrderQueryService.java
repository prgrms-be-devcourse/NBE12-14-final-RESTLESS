package com.restless.order.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.order.domain.Order;
import com.restless.order.domain.OrderItem;
import com.restless.order.infrastructure.OrderItemRepository;
import com.restless.order.domain.OrderType;
import com.restless.order.infrastructure.OrderRepository;
import com.restless.order.infrastructure.OrderShippingSnapshotRepository;
import com.restless.payment.application.PaymentLookup;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final MemberService members;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderShippingSnapshotRepository shippings;
    private final PaymentLookup payments;
    private final Map<OrderType, OrderPayableResolver> resolvers;

    public OrderQueryService(MemberService members, OrderRepository orders, OrderItemRepository items,
                             OrderShippingSnapshotRepository shippings, PaymentLookup payments,
                             List<OrderPayableResolver> resolvers) {
        this.members = members;
        this.orders = orders;
        this.items = items;
        this.shippings = shippings;
        this.payments = payments;
        this.resolvers = new EnumMap<>(OrderType.class);
        for (OrderPayableResolver resolver : resolvers) {
            if (this.resolvers.putIfAbsent(resolver.orderType(), resolver) != null) {
                throw new IllegalStateException("two payable resolvers for " + resolver.orderType());
            }
        }
    }

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

    /** 본인 주문 하나. 남의 주문과 없는 주문은 404 ORDER_NOT_FOUND다. 읽기만 한다. */
    @Transactional(readOnly = true)
    public OrderDetail getDetail(Long userId, Long orderId) {
        members.getActive(userId);
        Order order = orders.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        List<MyOrdersPage.Line> lines = items.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(item -> new MyOrdersPage.Line(item.getItemType(), item.getItemName(), item.getOptionName(),
                        item.getUnitPrice(), item.getQuantity()))
                .toList();
        OrderDetail.Shipping shipping = shippings.findById(orderId)
                .map(s -> new OrderDetail.Shipping(s.getRecipient(), s.getPhone(), s.getAddress()))
                .orElse(null);
        Optional<OrderPayable> payable = Optional.ofNullable(resolvers.get(order.getOrderType()))
                .flatMap(resolver -> resolver.resolve(orderId));
        OrderDetail.Payment payment = payable
                .flatMap(p -> payments.latest(p.payableType(), p.payableId(), userId))
                .map(latest -> new OrderDetail.Payment(latest.paymentId(), latest.status()))
                .orElse(null);
        return new OrderDetail(order.getId(), order.getOrderType(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), lines, shipping,
                payable.map(p -> new OrderDetail.Payable(p.payableType(), p.payableId())).orElse(null),
                payable.map(OrderPayable::expiresAt).orElse(null), payment);
    }
}
