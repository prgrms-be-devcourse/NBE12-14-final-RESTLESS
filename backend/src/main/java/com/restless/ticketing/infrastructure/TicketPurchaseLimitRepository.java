package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.TicketPurchaseLimit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TicketPurchaseLimitRepository extends JpaRepository<TicketPurchaseLimit, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TicketPurchaseLimit> findByUserIdAndPerformanceId(Long userId, Long performanceId);
}
