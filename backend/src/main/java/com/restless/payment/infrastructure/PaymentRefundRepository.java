package com.restless.payment.infrastructure;

import com.restless.payment.domain.PaymentRefund;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select r from PaymentRefund r where r.paymentId = :paymentId and r.status in"
                + " (com.restless.payment.domain.PaymentRefundStatus.REQUESTED,"
                + " com.restless.payment.domain.PaymentRefundStatus.UNKNOWN)")
    Optional<PaymentRefund> findPendingForUpdate(@Param("paymentId") Long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") Long id);
}
