package com.restless.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수락된 입찰. 거절된 입찰은 저장하지 않는다.
 * 상태 컬럼은 없고, 경매에서 {@code bid_sequence}가 가장 큰 입찰이 현재 최고 입찰이다 ({@link #statusIn}).
 */
@Entity
@Table(name = "auction_bids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auction_bid_id")
    private Long id;

    private Long auctionId;

    private Long bidderUserId;

    private long bidAmount;

    private long bidSequence;

    /** 회원별로 유일한 중복 요청 키 (Idempotency-Key). */
    private String requestKey;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant acceptedAt;

    public static Bid accept(Long auctionId, Long bidderUserId, long amount, long sequence, String requestKey,
                             Instant acceptedAt) {
        if (amount <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("amount and sequence must be positive");
        }
        Bid bid = new Bid();
        bid.auctionId = Objects.requireNonNull(auctionId);
        bid.bidderUserId = Objects.requireNonNull(bidderUserId);
        bid.bidAmount = amount;
        bid.bidSequence = sequence;
        bid.requestKey = Objects.requireNonNull(requestKey);
        bid.acceptedAt = Objects.requireNonNull(acceptedAt);
        return bid;
    }

    /**
     * 이 입찰의 현재 상태. 최고 입찰이면 경매가 진행 중일 때 HIGHEST, 종료됐으면 WON. 그 밖에는 OUTBID.
     *
     * @param highestBidId 그 경매의 현재 최고 입찰 ID
     */
    public BidStatus statusIn(Long highestBidId, boolean auctionClosed) {
        if (!id.equals(highestBidId)) {
            return BidStatus.OUTBID;
        }
        return auctionClosed ? BidStatus.WON : BidStatus.HIGHEST;
    }

    public boolean isBy(Long userId) {
        return bidderUserId.equals(userId);
    }
}
