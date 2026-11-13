package com.restless.concert.infrastructure;

import com.restless.concert.domain.Performance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findAllByConcertIdOrderByStartsAtAsc(Long concertId);
}
