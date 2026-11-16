package com.restless.auction.application;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.AuctionStatus;
import com.restless.auction.domain.Bid;
import com.restless.auction.domain.BidStatus;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.auction.infrastructure.BidRepository;
import com.restless.auction.infrastructure.MyBidRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 입찰 조회. 내가 입찰한 경매마다 마지막 입찰 하나를 상태와 함께 보여 준다. 잠그지 않는다. */
@Service
@RequiredArgsConstructor
public class MyBidQueryService {

    private final BidRepository bids;
    private final AuctionRepository auctions;

    @Transactional(readOnly = true)
    public MyBidPage myBids(Long userId, BidStatus status, int page, int size) {
        Page<MyBidRow> rows = bids.findMyLastBids(userId, status == null ? null : status.name(),
                PageRequest.of(page, size));
        List<Long> bidIds = rows.map(MyBidRow::getBidId).getContent();
        Map<Long, Bid> bidById = bids.findAllById(bidIds).stream()
                .collect(Collectors.toMap(Bid::getId, Function.identity()));
        Map<Long, Auction> auctionById = auctions.findAllById(
                        bidById.values().stream().map(Bid::getAuctionId).toList()).stream()
                .collect(Collectors.toMap(Auction::getId, Function.identity()));
        List<MyBid> items = rows.getContent().stream()
                .map(row -> {
                    Bid bid = bidById.get(row.getBidId());
                    return MyBid.of(auctionById.get(bid.getAuctionId()), bid, BidStatus.valueOf(row.getBidStatus()));
                })
                .toList();
        return new MyBidPage(items, rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
    }

    public record MyBidPage(List<MyBid> items, int page, int size, long totalElements, int totalPages) {
    }

    /** 경매 정보와 그 경매에서 내 마지막 입찰. 종료된 경매의 OUTBID는 화면에서 "낙찰 실패"로 표시한다. */
    public record MyBid(Long auctionId, String title, String itemName, AuctionStatus auctionStatus, Instant endsAt,
                        Long bidId, long amount, BidStatus status, Instant acceptedAt) {
        static MyBid of(Auction auction, Bid bid, BidStatus status) {
            return new MyBid(auction.getId(), auction.getTitle(), auction.getItemName(), auction.getStatus(),
                    auction.getEndsAt(), bid.getId(), bid.getBidAmount(), status, bid.getAcceptedAt());
        }
    }
}
