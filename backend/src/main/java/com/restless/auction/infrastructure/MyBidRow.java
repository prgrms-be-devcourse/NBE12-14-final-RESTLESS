package com.restless.auction.infrastructure;

/** 내 입찰 조회의 한 줄: 경매별 내 마지막 입찰 ID와 계산한 입찰 상태. */
public interface MyBidRow {

    Long getBidId();

    String getBidStatus();
}
