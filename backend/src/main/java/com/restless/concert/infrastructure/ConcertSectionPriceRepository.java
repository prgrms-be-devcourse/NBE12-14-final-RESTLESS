package com.restless.concert.infrastructure;

import com.restless.concert.domain.ConcertSectionPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertSectionPriceRepository extends JpaRepository<ConcertSectionPrice, Long> {
    List<ConcertSectionPrice> findAllByConcertIdOrderBySectionLabelAsc(Long concertId);
    boolean existsByConcertId(Long concertId);

}
