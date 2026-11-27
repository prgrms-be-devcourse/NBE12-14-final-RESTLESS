package com.restless.concert.infrastructure;

import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayoutSeatRepository extends JpaRepository<LayoutSeat, LayoutSeatId> {

    List<LayoutSeat> findAllById_LayoutId(Long layoutId);

    List<LayoutSeat> findAllById_LayoutIdIn(Collection<Long> layoutIds);

    /** 고른 물리 좌석 중 배치에서 판매 가능한 것만. 엔티티를 영속성 컨텍스트에 올리지 않는다. */
    @Query("select s.id.venueSeatId from LayoutSeat s "
            + "where s.id.layoutId = :layoutId and s.id.venueSeatId in :venueSeatIds and s.sellable = true")
    List<Long> findSellableVenueSeatIds(@Param("layoutId") Long layoutId,
                                        @Param("venueSeatIds") Collection<Long> venueSeatIds);
}
