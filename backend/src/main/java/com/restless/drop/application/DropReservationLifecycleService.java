package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.domain.DropReservationStatus;
import com.restless.drop.infrastructure.DropReservationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class DropReservationLifecycleService {
    private final DropReservationRepository reservations;
    private final DropReservationSettlement settlement;
    private final Clock clock;

    public Cancelled cancel(Long dropId, Long userId, Long reservationId) {
        var reservation = reservations.findByIdForUpdate(reservationId)
                .filter(r -> r.getDropId().equals(dropId) && r.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!reservation.cancel(clock.instant())) throw new BusinessException(ErrorCode.DROP_INVALID_STATE);
        settlement.release(reservation);
        return new Cancelled(reservationId, reservation.getStatus());
    }

    public boolean expire(Long reservationId) {
        var reservation = reservations.findByIdForUpdate(reservationId).orElse(null);
        // Re-read time only after acquiring the same lock used by payment approval start.
        if (reservation == null || !reservation.expire(clock.instant())) return false;
        settlement.release(reservation);
        return true;
    }

    public record Cancelled(Long reservationId, DropReservationStatus status) { }
}
