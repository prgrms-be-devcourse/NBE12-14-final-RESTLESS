package com.restless.auction.application;

import com.restless.auction.application.AuctionQueryService.BidPrecheckState;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 캐시 상태상 확실히 부족한 입찰만 경매 행 잠금 전에 거절한다. */
@Slf4j
@Service
public class BidPrecheckService {

    private final AuctionQueryCache queryCache;
    private final BidRepository bids;
    private final Clock clock;
    private final MeterRegistry meters;

    public BidPrecheckService(AuctionQueryCache queryCache, BidRepository bids, Clock clock, MeterRegistry meters) {
        this.queryCache = queryCache;
        this.bids = bids;
        this.clock = clock;
        this.meters = meters;
    }

    public void check(Long userId, Long auctionId, long amount, String requestKey) {
        long minimumBidAmount;
        try {
            Optional<BidPrecheckState> cachedState = queryCache.bidPrecheckState(auctionId);
            if (cachedState.isEmpty()) {
                return;
            }

            BidPrecheckState state = cachedState.get();
            Instant now = clock.instant();
            if (state.status() != AuctionStatus.OPEN
                    || now.isBefore(state.startsAt()) || !now.isBefore(state.endsAt())
                    || Objects.equals(userId, state.sellerUserId())
                    || Objects.equals(userId, state.highestBidderUserId())
                    || amount >= state.minimumBidAmount()) {
                return;
            }

            if (bids.findByBidderUserIdAndRequestKey(userId, requestKey).isPresent()) {
                return;
            }
            minimumBidAmount = state.minimumBidAmount();
        } catch (RuntimeException exception) {
            log.warn("Auction bid precheck failed auctionId={}; continuing through locked path", auctionId, exception);
            meters.counter("auction.bid.precheck.failure", "stage", "check").increment();
            return;
        }

        throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW, Map.of("minimumBidAmount", minimumBidAmount));
    }
}
