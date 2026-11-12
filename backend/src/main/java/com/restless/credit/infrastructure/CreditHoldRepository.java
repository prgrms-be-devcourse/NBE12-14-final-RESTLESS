package com.restless.credit.infrastructure;

import com.restless.credit.domain.CreditHold;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditHoldRepository extends JpaRepository<CreditHold, Long> {

    Optional<CreditHold> findByAuctionBidId(Long auctionBidId);
}
