package com.restless.auction.infrastructure;

import com.restless.auction.domain.CreditHold;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditHoldRepository extends JpaRepository<CreditHold, Long> {

    Optional<CreditHold> findByAuctionBidId(Long auctionBidId);
}
