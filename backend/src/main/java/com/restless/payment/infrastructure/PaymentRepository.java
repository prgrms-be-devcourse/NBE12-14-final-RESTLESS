package com.restless.payment.infrastructure;

import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.Payment;
import com.restless.payment.domain.PaymentStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    // One bounded query per status, using idx_payments_status_updated_at.
    List<Payment> findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAscIdAsc(
            PaymentStatus status, Instant cutoff, Pageable page);

    List<Payment> findByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(
            PaymentStatus status, Instant cutoff, Pageable page);

    @Query(
            "select count(p) as count, min(p.unknownSinceAt) as oldest from Payment p where"
                + " p.status = com.restless.payment.domain.PaymentStatus.UNKNOWN")
    UnknownSummary summarizeUnknown();

    interface UnknownSummary {
        long getCount();

        Instant getOldest();
    }

    Optional<Payment> findByPgOrderId(String pgOrderId);

    /** 결제 대상의 가장 최근 시도. 시도 ID가 클수록 최근이다. */
    Optional<Payment> findFirstByPayableTypeAndPayableIdAndUserIdOrderByIdDesc(
            PayableType payableType, Long payableId, Long userId);

    Optional<Payment> findByPayableTypeAndPayableIdAndIdempotencyKey(
            PayableType payableType, Long payableId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select p from Payment p where p.payableType = :type and p.payableId = :payableId and"
                + " p.status not in (com.restless.payment.domain.PaymentStatus.FAILED,"
                + " com.restless.payment.domain.PaymentStatus.CANCELLED)")
    Optional<Payment> findActiveForUpdate(
            @Param("type") PayableType type, @Param("payableId") Long payableId);

    /**
     * Locks the payment row (SELECT ... FOR UPDATE) for the rest of the caller's transaction. Use
     * this before calling entity transition methods: plain loads let two concurrent transactions
     * both see PROCESSING and both approve. Lock the payment row before any domain rows (ADR-0003).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.pgOrderId = :pgOrderId")
    Optional<Payment> findByPgOrderIdForUpdate(@Param("pgOrderId") String pgOrderId);
}
