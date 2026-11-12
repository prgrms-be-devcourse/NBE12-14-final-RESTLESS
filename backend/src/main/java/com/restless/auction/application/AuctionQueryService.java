package com.restless.auction.application;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auction.domain.Bid;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 경매 목록·상세 조회. 잠그지 않는다. */
@Service
@RequiredArgsConstructor
public class AuctionQueryService {

    private final AuctionRepository auctions;
    private final BidRepository bids;
    private final MemberService memberService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AuctionPage list(AuctionStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, sortFor(status));
        Page<Auction> result = status == null ? auctions.findAll(pageable) : auctions.findByStatus(status, pageable);
        return new AuctionPage(result.map(AuctionSummary::of).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AuctionDetail detail(Long auctionId) {
        Auction auction = auctions.findById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        Bid highest = bids.findTopByAuctionIdOrderByBidSequenceDesc(auctionId).orElse(null);
        List<Long> userIds = new ArrayList<>(List.of(auction.getSellerUserId()));
        if (highest != null) {
            userIds.add(highest.getBidderUserId());
        }
        Map<Long, String> nicknames = memberService.nicknames(userIds);
        HighestBid highestBid = highest == null ? null
                : new HighestBid(nicknames.get(highest.getBidderUserId()), highest.getBidAmount(), highest.getAcceptedAt());
        return new AuctionDetail(auction.getId(), auction.getTitle(), auction.getItemName(), auction.getItemDescription(),
                nicknames.get(auction.getSellerUserId()), auction.getStatus(), auction.getStartingPriceAmount(),
                auction.getMinimumIncrementAmount(), highestBid,
                auction.minimumBidAmount(highest == null ? null : highest.getBidAmount()),
                auction.getStartsAt(), auction.getEndsAt(), auction.getExtensionDurationSeconds(),
                auction.getRevision(), clock.instant());
    }

    /** 진행 중은 곧 끝나는 순, 예정은 곧 시작하는 순, 종료와 전체는 최근 순. */
    private static Sort sortFor(AuctionStatus status) {
        if (status == AuctionStatus.OPEN) return Sort.by("endsAt").ascending().and(Sort.by("id"));
        if (status == AuctionStatus.SCHEDULED) return Sort.by("startsAt").ascending().and(Sort.by("id"));
        if (status == AuctionStatus.CLOSED) return Sort.by("endsAt").descending().and(Sort.by("id").descending());
        return Sort.by("id").descending();
    }

    public record AuctionPage(List<AuctionSummary> items, int page, int size, long totalElements, int totalPages) {
    }

    public record AuctionSummary(Long auctionId, String title, String itemName, AuctionStatus status,
                                 long startingPriceAmount, Instant startsAt, Instant endsAt) {
        static AuctionSummary of(Auction a) {
            return new AuctionSummary(a.getId(), a.getTitle(), a.getItemName(), a.getStatus(),
                    a.getStartingPriceAmount(), a.getStartsAt(), a.getEndsAt());
        }
    }

    public record HighestBid(String nickname, long amount, Instant acceptedAt) {
    }

    public record AuctionDetail(Long auctionId, String title, String itemName, String itemDescription,
                                String sellerNickname, AuctionStatus status, long startingPriceAmount,
                                long minimumIncrementAmount, HighestBid highestBid, long minimumBidAmount,
                                Instant startsAt, Instant endsAt, int extensionDurationSeconds, long revision,
                                Instant serverTime) {
    }
}
