package com.restless.auction.domain;

/**
 * 입찰 상태. DB에 저장하지 않고 계산한다 (docs/domains/auction/state-machines.md 2절).
 * 종료된 경매에서 낙찰되지 못한 입찰은 OUTBID다.
 */
public enum BidStatus {
    /** 현재 최고 입찰 (경매가 끝나지 않음) */
    HIGHEST,
    /** 더 높은 입찰에 추월당함 */
    OUTBID,
    /** 이 입찰이 낙찰됨 */
    WON
}
