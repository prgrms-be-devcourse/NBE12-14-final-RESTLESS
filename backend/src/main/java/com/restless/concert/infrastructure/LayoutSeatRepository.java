package com.restless.concert.infrastructure;

import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LayoutSeatRepository extends JpaRepository<LayoutSeat, LayoutSeatId> {

    List<LayoutSeat> findAllById_LayoutId(Long layoutId);

    List<LayoutSeat> findAllById_LayoutIdIn(Collection<Long> layoutIds);
}
