package com.restless.auction.application;

import com.restless.auction.application.AuctionQueryService.BidPrecheckState;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 캐시 상태상 확실히 부족한 입찰만 경매 행 잠금 전에 거절한다.
 *
 * <p>판단마다 {@code auction.bid.precheck{result, reason}}을 센다 (#448). 통과(pass)는 잠금 경로로 가는 요청이고,
 * reason은 왜 미리 거절하지 않았는지다. 몰림 측정에서 잠금 대기가 왜 생기는지 나눠 보려고 둔다.
 */
@Slf4j
@Service
public class BidPrecheckService {

    private final AuctionQueryCache queryCache;
    private final BidRepository bids;
    private final Clock clock;
    private final MeterRegistry meters;

    static final String METRIC = "auction.bid.precheck";

    /** 판단 결과. 이름은 지표의 reason 값이다. */
    enum Outcome {
        /** 캐시에 경매 상태가 없다 (캐시 꺼짐, 없는 경매). */
        NO_STATE("pass"),
        /** 진행 중이 아니다. 시작 전, 종료 시각 지남, 상태가 OPEN이 아님. 잠금 경로가 그에 맞는 오류를 준다. */
        NOT_OPEN("pass"),
        SELLER("pass"),
        HIGHEST_BIDDER("pass"),
        /** 캐시의 최소 금액 이상이다. 잠금 경로에서 실제 최고가와 다시 비교한다. */
        AMOUNT_OK("pass"),
        /** 같은 요청 키의 입찰이 이미 있다. 처음 결과를 돌려주도록 잠금 경로로 보낸다. */
        REPLAY("pass"),
        /** 확인 중 오류. 기존 경로로 처리한다. */
        ERROR("pass"),
        AMOUNT_TOO_LOW("reject");

        private final String result;

        Outcome(String result) {
            this.result = result;
        }

        String reason() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

    public BidPrecheckService(AuctionQueryCache queryCache, BidRepository bids, Clock clock, MeterRegistry meters) {
        this.queryCache = queryCache;
        this.bids = bids;
        this.clock = clock;
        this.meters = meters;
        // 측정 전부터 0으로 보이도록 미리 등록한다. 입찰마다 레지스트리를 찾지 않는다.
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, meters.counter(METRIC, "result", outcome.result, "reason", outcome.reason()));
        }
    }

    public void check(Long userId, Long auctionId, long amount, String requestKey) {
        Outcome outcome;
        long minimumBidAmount = 0;
        try {
            Optional<BidPrecheckState> cachedState = queryCache.bidPrecheckState(auctionId);
            if (cachedState.isEmpty()) {
                outcome = Outcome.NO_STATE;
            } else {
                BidPrecheckState state = cachedState.get();
                minimumBidAmount = state.minimumBidAmount();
                outcome = judge(state, userId, amount);
                if (outcome == Outcome.AMOUNT_TOO_LOW
                        && bids.findByBidderUserIdAndRequestKey(userId, requestKey).isPresent()) {
                    outcome = Outcome.REPLAY;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Auction bid precheck failed auctionId={}; continuing through locked path", auctionId, exception);
            meters.counter("auction.bid.precheck.failure", "stage", "check").increment();
            outcome = Outcome.ERROR;
        }

        outcomes.get(outcome).increment();
        if (outcome == Outcome.AMOUNT_TOO_LOW) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW, Map.of("minimumBidAmount", minimumBidAmount));
        }
    }

    /** 앞의 조건부터 본다. 금액 부족 후보만 요청 키를 따로 조회한다. */
    private Outcome judge(BidPrecheckState state, Long userId, long amount) {
        Instant now = clock.instant();
        if (state.status() != AuctionStatus.OPEN || now.isBefore(state.startsAt()) || !now.isBefore(state.endsAt())) {
            return Outcome.NOT_OPEN;
        }
        if (Objects.equals(userId, state.sellerUserId())) {
            return Outcome.SELLER;
        }
        if (Objects.equals(userId, state.highestBidderUserId())) {
            return Outcome.HIGHEST_BIDDER;
        }
        if (amount >= state.minimumBidAmount()) {
            return Outcome.AMOUNT_OK;
        }
        return Outcome.AMOUNT_TOO_LOW;
    }
}
