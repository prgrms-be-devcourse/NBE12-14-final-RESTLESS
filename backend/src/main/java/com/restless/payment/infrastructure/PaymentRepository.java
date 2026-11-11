package com.restless.payment.infrastructure;

import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.Payment;
import com.restless.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPgOrderId(String pgOrderId);
    Optional<Payment> findByPayableTypeAndPayableIdAndIdempotencyKey(
        PayableType payableType, Long payableId, String idempotencyKey);

    /**
     * Caller supplies a transaction and validated source/target states.
     * A zero result means another worker already resolved this attempt.
     * Bulk updates clear the persistence context; callers must reload before further entity changes.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :to, p.updatedAt = :now where p.id = :id and p.status in :from")
    int transitionStatus(@Param("id") Long id, @Param("from") Collection<PaymentStatus> from,
                         @Param("to") PaymentStatus to, @Param("now") Instant now);
}
