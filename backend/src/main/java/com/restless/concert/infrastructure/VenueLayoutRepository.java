package com.restless.concert.infrastructure;

import com.restless.concert.domain.VenueLayout;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueLayoutRepository extends JpaRepository<VenueLayout, Long> {
    List<VenueLayout> findAllByVenueIdOrderByIdAsc(Long venueId);
}
