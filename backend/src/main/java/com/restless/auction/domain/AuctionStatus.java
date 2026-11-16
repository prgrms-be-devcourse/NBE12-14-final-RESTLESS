package com.restless.auction.domain;

/**
 * 경매 상태. 상태는 시작·종료 스케줄러가 바꾼다 (docs/domains/auction/state-machines.md 1절).
 * 입찰 가능 여부는 상태가 아니라 시각({@code starts_at <= 지금 < ends_at})으로 판단한다.
 */
public enum AuctionStatus {
    SCHEDULED,
    OPEN,
    CLOSED
}
