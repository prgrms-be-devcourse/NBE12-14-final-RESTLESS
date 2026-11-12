package com.restless.ticketing.infrastructure;

import com.restless.ticketing.domain.SeatHold;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    List<SeatHold> findAllByReservationIdOrderByPerformanceSeatIdAsc(Long reservationId);
}
