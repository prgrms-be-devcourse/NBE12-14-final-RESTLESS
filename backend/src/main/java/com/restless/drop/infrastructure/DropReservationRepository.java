package com.restless.drop.infrastructure;

import com.restless.drop.domain.DropReservation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DropReservationRepository extends JpaRepository<DropReservation, Long> {
    Optional<DropReservation> findByDropIdAndUserIdAndRequestKey(Long dropId, Long userId, String requestKey);
    Optional<DropReservation> findByIdAndDropIdAndUserId(Long id, Long dropId, Long userId);
}
