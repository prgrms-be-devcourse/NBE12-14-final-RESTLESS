package com.restless.auction.application;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.AuctionResult;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auction.domain.Bid;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.auction.infrastructure.AuctionResultRepository;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.credit.application.CreditWalletService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경매 시작(TX-AU-3)과 종료(TX-AU-4). 스케줄러가 부른다.
 *
 * <p>경매 하나를 트랜잭션 하나로 처리한다. 한 경매가 실패해도 다른 경매에 영향이 없고, 다음 주기에 다시 시도한다.
 * 모든 처리는 경매 행을 잠근 뒤 조건을 다시 확인하므로, 여러 번 실행되거나 입찰과 겹쳐도 결과는 하나다.
 */
@Service
@RequiredArgsConstructor
public class AuctionCloseService {

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final AuctionResultRepository results;
    private final CreditWalletService creditWalletService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** 시작 시각이 지난 예정 경매 ID. 잠그지 않는 후보 조회다. */
    @Transactional(readOnly = true)
    public List<Long> findDueToOpen(int limit) {
        return auctions.findIdsToOpen(AuctionStatus.SCHEDULED, clock.instant(), PageRequest.of(0, limit));
    }

    /** 종료 시각이 지난, 아직 종료되지 않은 경매 ID. 시작 처리를 놓친 예정 경매도 포함한다. */
    @Transactional(readOnly = true)
    public List<Long> findDueToClose(int limit) {
        return auctions.findIdsToClose(List.of(AuctionStatus.SCHEDULED, AuctionStatus.OPEN), clock.instant(),
                PageRequest.of(0, limit));
    }

    /** SCHEDULED → OPEN. 잠금 뒤 다시 확인하고, 바꿨으면 true. */
    @Transactional
    public boolean open(Long auctionId) {
        Auction auction = auctions.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null || !auction.openIfDue(clock.instant())) {
            return false;
        }
        events.publishEvent(new AuctionChangedEvent(auctionId, auction.getRevision()));
        return true;
    }

    /**
     * 종료 (TX-AU-4). 경매 행을 잠근 뒤 종료 시각을 다시 확인한다. 그사이 입찰로 연장됐으면 아무것도 하지 않는다.
     * 최고 입찰이 있으면 그 확보를 차감하고 낙찰(SOLD), 없으면 유찰(UNSOLD). 결과·차감·상태를 함께 커밋한다 (INV-AU-13).
     *
     * @return 이번 호출로 종료했으면 true
     */
    @Transactional
    public boolean close(Long auctionId) {
        Auction auction = auctions.findByIdForUpdate(auctionId).orElse(null);
        Instant now = clock.instant();
        if (auction == null || !auction.isDueToClose(now)) {
            return false;
        }
        Bid highest = bids.findTopByAuctionIdOrderByBidSequenceDesc(auctionId).orElse(null);
        if (highest == null) {
            results.save(AuctionResult.unsold(auctionId, now));
        } else {
            creditWalletService.capture(highest.getId());
            results.save(AuctionResult.sold(auctionId, highest.getId(), now));
        }
        auction.close(now);
        events.publishEvent(new AuctionChangedEvent(auctionId, auction.getRevision()));
        return true;
    }
}
