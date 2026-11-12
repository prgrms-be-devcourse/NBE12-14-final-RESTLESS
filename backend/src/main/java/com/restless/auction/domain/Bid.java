package com.restless.auction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 수락된 입찰. 상태 컬럼은 없고, 경매에서 {@code bid_sequence}가 가장 큰 입찰이 현재 최고 입찰이다.
 * 입찰 생성은 4단계(BidService)에서 추가한다.
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

    private String requestKey;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant acceptedAt;
}
