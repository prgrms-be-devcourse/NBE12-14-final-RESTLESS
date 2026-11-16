package com.restless.auction.infrastructure;

import com.restless.auction.domain.Bid;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BidRepository extends JpaRepository<Bid, Long> {

    /** 현재 최고 입찰 = 가장 큰 bid_sequence. UNIQUE(auction_id, bid_sequence) 인덱스를 쓴다. */
    Optional<Bid> findTopByAuctionIdOrderByBidSequenceDesc(Long auctionId);

    /** 중복 요청 확인. UNIQUE(bidder_user_id, request_key) 인덱스를 쓴다. */
    Optional<Bid> findByBidderUserIdAndRequestKey(Long bidderUserId, String requestKey);

    /**
     * 경매별 내 마지막 입찰과 그 상태 (state-machines.md 2절). {@link com.restless.auction.domain.Bid#statusIn}과 같은 규칙이다.
     * 그 경매의 마지막 입찰이 아니면 OUTBID, 마지막 입찰이면 경매가 CLOSED일 때 WON, 아니면 HIGHEST.
     */
    String MY_LAST_BIDS = """
        FROM (
          SELECT b.auction_bid_id, b.accepted_at,
                 CASE WHEN b.bid_sequence < (SELECT MAX(x.bid_sequence) FROM auction_bids x
                                             WHERE x.auction_id = b.auction_id) THEN 'OUTBID'
                      WHEN a.status = 'CLOSED' THEN 'WON'
                      ELSE 'HIGHEST' END AS bid_status
          FROM auction_bids b
          JOIN auctions a ON a.auction_id = b.auction_id
          WHERE b.bidder_user_id = :userId
            AND b.bid_sequence = (SELECT MAX(y.bid_sequence) FROM auction_bids y
                                  WHERE y.auction_id = b.auction_id AND y.bidder_user_id = :userId)
        ) t
        WHERE (:status IS NULL OR t.bid_status = :status)
        """;

    /** 최근에 입찰한 경매부터. status가 null이면 전체. */
    @Query(value = "SELECT t.auction_bid_id AS bidId, t.bid_status AS bidStatus " + MY_LAST_BIDS
            + " ORDER BY t.accepted_at DESC, t.auction_bid_id DESC",
            countQuery = "SELECT COUNT(*) " + MY_LAST_BIDS,
            nativeQuery = true)
    Page<MyBidRow> findMyLastBids(@Param("userId") Long userId, @Param("status") String status, Pageable pageable);
}
