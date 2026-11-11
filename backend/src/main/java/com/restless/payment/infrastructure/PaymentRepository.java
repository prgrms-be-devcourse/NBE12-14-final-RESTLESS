package com.restless.payment.infrastructure;

import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPgOrderId(String pgOrderId);
    Optional<Payment> findByPayableTypeAndPayableIdAndIdempotencyKey(
        PayableType payableType, Long payableId, String idempotencyKey);

    /**
     * Locks the payment row (SELECT ... FOR UPDATE) for the rest of the caller's transaction.
     * Use this before calling entity transition methods: plain loads let two concurrent
     * transactions both see PROCESSING and both approve. Lock the payment row before any
     * domain rows (ADR-0003).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.pgOrderId = :pgOrderId")
    Optional<Payment> findByPgOrderIdForUpdate(@Param("pgOrderId") String pgOrderId);
}
