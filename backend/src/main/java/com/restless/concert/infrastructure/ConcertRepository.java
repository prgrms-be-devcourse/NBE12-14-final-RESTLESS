package com.restless.concert.infrastructure;

import com.restless.concert.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface ConcertRepository extends JpaRepository<Concert, Long> {
    Page<Concert> findAllByPublishedTrue(Pageable pageable);

    java.util.Optional<Concert> findByIdAndPublishedTrue(Long id);

    @org.springframework.data.jpa.repository.Query("select c.published from Concert c join Performance p on p.concertId = c.id where p.id = :performanceId")
    Optional<Boolean> isPerformancePublished(@org.springframework.data.repository.query.Param("performanceId") Long performanceId);
}
