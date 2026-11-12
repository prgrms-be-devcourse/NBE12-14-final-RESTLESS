package com.restless.auction.application;

/**
 * 경매 상태가 바뀌었다 (입찰 수락, 시작, 종료). 트랜잭션 안에서 발행하고,
 * 받는 쪽은 AFTER_COMMIT 리스너로만 처리한다 (ADR-0001). 실시간 전달(6단계)이 받는다.
 */
public record AuctionChangedEvent(Long auctionId, long revision) {
}
