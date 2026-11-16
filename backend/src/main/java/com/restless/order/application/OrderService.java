package com.restless.order.application;

import com.restless.order.domain.Order;
import com.restless.order.domain.OrderItem;
import com.restless.order.domain.OrderShippingSnapshot;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.util.Collection;
import com.restless.order.infrastructure.OrderItemRepository;
import com.restless.order.infrastructure.OrderRepository;
import com.restless.order.infrastructure.OrderShippingSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * 공통 주문의 생성과 상태 전이. 드롭, 티켓팅이 자기 트랜잭션 안에서 호출한다.
 * 다른 도메인은 주문 Repository를 직접 쓰지 않고 이 서비스를 쓴다 (AGENTS.md 5절).
 *
 * <p>모든 쓰기 메서드는 호출자의 트랜잭션에 참여한다({@code MANDATORY}). 주문만 따로 커밋되어
 * 예약·재고와 어긋나는 일을 막는다. 잠금 순서: 결제 행 → 도메인 행(예약 등) → 주문 행.
 */
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderShippingSnapshotRepository shippingSnapshots;
    private final Clock clock;

    /**
     * {@code PENDING_PAYMENT} 주문과 항목·배송지 스냅샷을 만든다. 총액은 항목 금액의 합이다.
     *
     * @return 주문 ID
     * @throws IllegalArgumentException 항목·배송지 값이 잘못됨, 금액이 long 범위를 넘음
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long create(CreateOrderCommand command) {
        var now = clock.instant();
        long total = 0;
        for (var line : command.lines()) {
            if (line.quantity() <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (line.unitPrice() < 0) throw new IllegalArgumentException("unitPrice must be nonnegative");
            try {
                total = Math.addExact(total, Math.multiplyExact(line.unitPrice(), line.quantity()));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("order amount overflows", e);
            }
        }
        Order order = orders.save(Order.pendingPayment(command.userId(), command.orderType(), total, now));
        Long orderId = order.getId();
        items.saveAll(command.lines().stream()
            .map(line -> OrderItem.snapshot(orderId, line.itemType(), line.itemRefId(), line.itemName(),
                line.optionName(), line.unitPrice(), line.quantity()))
            .toList());
        if (command.shipping() != null) {
            var shipping = command.shipping();
            shippingSnapshots.save(OrderShippingSnapshot.of(orderId, shipping.recipient(), shipping.phone(),
                shipping.address()));
        }
        return orderId;
    }

    @Transactional(readOnly = true)
    public Optional<OrderView> find(Long orderId) {
        return orders.findById(orderId).map(OrderView::from);
    }

    /** 지정한 회원·도메인에 해당 상태의 주문이 있는지 조회한다. */
    @Transactional(readOnly = true)
    public boolean existsByUserAndTypeAndStatuses(Long userId,
            OrderType orderType, Collection<OrderStatus> statuses) {
        return orders.existsByUserIdAndOrderTypeAndStatusIn(userId, orderType, statuses);
    }

    /**
     * 주문 행을 잠그고 상태를 바꾼다. 전이 규칙은 {@link OrderStatus}에 있다.
     *
     * @return 바뀌었으면 true. 이미 target이면 false (같은 결과의 중복 반영, INV-11)
     * @throws IllegalArgumentException 주문이 없음
     * @throws IllegalStateException    허용되지 않는 전이. 호출자의 트랜잭션 전체가 롤백된다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean changeStatus(Long orderId, OrderStatus target) {
        Order order = orders.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return order.transitionTo(target, clock.instant());
    }
}
