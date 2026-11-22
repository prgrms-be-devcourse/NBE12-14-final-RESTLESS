package com.restless.credit.infrastructure;

import com.restless.credit.domain.CreditCharge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditChargeRepository extends JpaRepository<CreditCharge, Long> {
    Optional<CreditCharge> findByUserIdAndRequestKey(Long userId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditCharge c where c.id = :id")
    Optional<CreditCharge> findByIdForUpdate(@Param("id") Long id);
}
