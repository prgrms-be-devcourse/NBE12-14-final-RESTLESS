package com.restless.order.infrastructure;

import com.restless.order.domain.Order;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    boolean existsByUserIdAndOrderTypeAndStatusIn(Long userId,
            OrderType orderType, Collection<OrderStatus> statuses);

    /**
     * 주문 행을 잠근다 (SELECT ... FOR UPDATE). 상태 전이 전에 쓴다.
     * 잠금 순서: 결제 행 → 도메인 행(예약 등) → 주문 행 (ADR-0003).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
