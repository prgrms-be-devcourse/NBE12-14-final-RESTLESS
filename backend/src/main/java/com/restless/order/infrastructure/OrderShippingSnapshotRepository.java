package com.restless.order.infrastructure;

import com.restless.order.domain.OrderShippingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderShippingSnapshotRepository extends JpaRepository<OrderShippingSnapshot, Long> {
}
