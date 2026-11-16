package com.restless.auction.domain;

/** 경매 결과 ({@code auction_results.result_type}). 입찰 상태 {@link BidStatus#WON}과 구분한다. */
public enum AuctionResultType {
    /** 낙찰: 최고 입찰자가 낙찰받음. winning_bid_id 필수 */
    SOLD,
    /** 유찰: 입찰 없이 종료. winning_bid_id 없음 */
    UNSOLD
}
