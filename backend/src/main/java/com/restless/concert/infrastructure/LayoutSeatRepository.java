package com.restless.concert.infrastructure;

import com.restless.concert.application.dto.SeatPosition;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayoutSeatRepository extends JpaRepository<LayoutSeat, LayoutSeatId> {

    List<LayoutSeat> findAllById_LayoutId(Long layoutId);

    @Query("select new com.restless.concert.application.dto.SeatPosition(s.id.venueSeatId, s.sectionLabel, "
            + "s.rowLabel, s.seatNumber, s.posX, s.posY) from LayoutSeat s where s.id.layoutId = :layoutId")
    List<SeatPosition> findPositionsByLayoutId(@Param("layoutId") Long layoutId);
}
