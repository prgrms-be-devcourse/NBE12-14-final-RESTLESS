package com.restless.auction.application;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.Bid;
import com.restless.auction.domain.BidStatus;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.credit.application.CreditWalletService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입찰 (docs/domains/auction/transactions.md TX-AU-2).
 *
 * <p>입찰 저장, 새 입찰 크레딧 확보, 이전 최고 입찰 크레딧 해제, 종료 시각 연장을 한 트랜잭션에서 처리한다.
 * 업무 거절은 BusinessException으로 던지고 전체를 롤백한다. 거절된 입찰은 남지 않는다.
 *
 * <p><b>경매 행 잠금을 가장 먼저 잡는다.</b> MySQL(REPEATABLE READ)은 첫 일반 조회 때 읽기 시점을 고정하므로,
 * 잠금 전에 다른 조회를 하면 앞사람이 커밋한 최고 입찰을 못 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
public class BidService {

    static final String REQUEST_KEY_UNIQUE = "uk_auction_bids_bidder_request_key";

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final CreditWalletService creditWalletService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public BidResult place(Long userId, Long auctionId, long amount, String requestKey) {
        Auction auction = auctions.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        Instant now = clock.instant();

        Optional<Bid> previous = bids.findByBidderUserIdAndRequestKey(userId, requestKey);
        if (previous.isPresent()) {
            return replay(previous.get(), auction, amount);
        }

        auction.requireBiddingOpen(now);
        if (auction.isSeller(userId)) {
            throw new BusinessException(ErrorCode.BID_BY_SELLER);
        }
        Bid highest = bids.findTopByAuctionIdOrderByBidSequenceDesc(auctionId).orElse(null);
        if (highest != null && highest.isBy(userId)) {
            throw new BusinessException(ErrorCode.BID_ALREADY_HIGHEST);
        }
        long minimum = auction.minimumBidAmount(highest == null ? null : highest.getBidAmount());
        if (amount < minimum) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW, Map.of("minimumBidAmount", minimum));
        }

        List<Long> walletOwners = new ArrayList<>(List.of(userId));
        if (highest != null) {
            walletOwners.add(highest.getBidderUserId());
        }
        creditWalletService.lockWallets(walletOwners);

        long sequence = highest == null ? 1 : highest.getBidSequence() + 1;
        Bid bid = save(Bid.accept(auctionId, userId, amount, sequence, requestKey, now));
        creditWalletService.hold(userId, bid.getId(), amount);
        if (highest != null) {
            creditWalletService.release(highest.getId());
        }
        auction.acceptBid(now);
        events.publishEvent(new AuctionChangedEvent(auctionId, auction.getRevision()));
        return BidResult.of(bid, BidStatus.HIGHEST, auction);
    }

    /** 같은 키로 이미 수락된 입찰. 같은 경매·금액이면 그 입찰을 현재 상태와 함께 돌려준다. 다시 처리하지 않는다. */
    private BidResult replay(Bid previous, Auction auction, long amount) {
        if (!previous.getAuctionId().equals(auction.getId()) || previous.getBidAmount() != amount) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        Long highestId = bids.findTopByAuctionIdOrderByBidSequenceDesc(auction.getId()).map(Bid::getId).orElse(null);
        return BidResult.of(previous, previous.statusIn(highestId, auction.isClosed()), auction);
    }

    /**
     * 같은 키가 다른 경매로 동시에 오면 서로 다른 경매 행을 잠가 위의 확인을 둘 다 통과한다.
     * 그때는 UNIQUE(bidder_user_id, request_key)가 두 번째 저장을 막는다.
     */
    private Bid save(Bid bid) {
        try {
            return bids.saveAndFlush(bid);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(REQUEST_KEY_UNIQUE)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            throw e;
        }
    }

    public record BidResult(Long bidId, Long auctionId, long amount, BidStatus status, Instant acceptedAt,
                            Instant endsAt) {
        static BidResult of(Bid bid, BidStatus status, Auction auction) {
            return new BidResult(bid.getId(), bid.getAuctionId(), bid.getBidAmount(), status, bid.getAcceptedAt(),
                    auction.getEndsAt());
        }
    }
}
