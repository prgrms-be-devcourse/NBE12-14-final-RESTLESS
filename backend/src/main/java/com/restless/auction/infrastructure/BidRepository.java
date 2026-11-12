package com.restless.auction.infrastructure;

import com.restless.auction.domain.Bid;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BidRepository extends JpaRepository<Bid, Long> {

    /** 현재 최고 입찰 = 가장 큰 bid_sequence. UNIQUE(auction_id, bid_sequence) 인덱스를 쓴다. */
    Optional<Bid> findTopByAuctionIdOrderByBidSequenceDesc(Long auctionId);

    /** 중복 요청 확인. UNIQUE(bidder_user_id, request_key) 인덱스를 쓴다. */
    Optional<Bid> findByBidderUserIdAndRequestKey(Long bidderUserId, String requestKey);
}
