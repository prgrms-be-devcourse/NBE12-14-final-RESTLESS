package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropReservation;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DropReservationRepository extends JpaRepository<DropReservation, Long> {
    Optional<DropReservation> findByDropIdAndUserIdAndRequestKey(Long dropId, Long userId, String requestKey);
    Optional<DropReservation> findByIdAndDropIdAndUserId(Long id, Long dropId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DropReservation r where r.id = :id")
    Optional<DropReservation> findByIdForUpdate(Long id);

    @Query("select r.id from DropReservation r where r.status = com.restless.drop.domain.DropReservationStatus.RESERVED "
            + "and r.expiresAt <= :now order by r.expiresAt, r.id")
    List<Long> findExpiryCandidates(Instant now, Pageable page);
}
