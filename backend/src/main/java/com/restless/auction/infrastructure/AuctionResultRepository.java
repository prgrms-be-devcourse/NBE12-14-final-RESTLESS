package com.restless.auction.infrastructure;

import com.restless.auction.domain.AuctionResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionResultRepository extends JpaRepository<AuctionResult, Long> {
}
