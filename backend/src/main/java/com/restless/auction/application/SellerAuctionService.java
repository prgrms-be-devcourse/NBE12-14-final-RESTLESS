package com.restless.auction.application;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.Auction.Terms;
import com.restless.auction.infrastructure.AuctionRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주최자의 경매 등록, 수정, 삭제 (TX-AU-5).
 *
 * <p>역할(ORGANIZER, ADMIN)은 Security가 먼저 막고, 여기서는 본인 경매인지와 시작 전인지를 확인한다.
 * ADMIN은 다른 주최자의 경매도 수정·삭제할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class SellerAuctionService {

    private final AuctionRepository auctions;
    private final MemberService memberService;
    private final Clock clock;

    @Transactional
    public Long create(Long sellerUserId, Terms terms) {
        return auctions.save(Auction.schedule(sellerUserId, terms, clock.instant())).getId();
    }

    @Transactional
    public Long update(Long userId, Long auctionId, Terms terms) {
        Instant now = clock.instant();
        Auction auction = lockOwned(userId, auctionId);
        auction.update(terms, now);
        return auction.getId();
    }

    @Transactional
    public void delete(Long userId, Long auctionId) {
        Instant now = clock.instant();
        Auction auction = lockOwned(userId, auctionId);
        auction.requireNotStarted(now);
        auctions.delete(auction);
    }

    /** 경매 행을 잠그고 본인(또는 ADMIN)인지 확인한다. 시작 여부는 잠금 뒤에 호출한 쪽이 확인한다. */
    private Auction lockOwned(Long userId, Long auctionId) {
        Auction auction = auctions.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        if (!auction.isSeller(userId) && memberService.getActive(userId).getRole() != MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.AUCTION_FORBIDDEN);
        }
        return auction;
    }
}
