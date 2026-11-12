package com.restless.auction.api;

import com.restless.auction.api.dto.AuctionTermsRequest;
import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.application.AuctionQueryService.AuctionDetail;
import com.restless.auction.application.SellerAuctionService;
import com.restless.auth.LoginMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 경매 등록, 수정, 삭제. ORGANIZER, ADMIN만 (SecurityConfig). */
@RestController
@RequestMapping("/api/auctions")
@RequiredArgsConstructor
public class SellerAuctionController {

    private final SellerAuctionService sellerAuctionService;
    private final AuctionQueryService auctionQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedAuction create(@LoginMember Long userId, @Valid @RequestBody AuctionTermsRequest request) {
        return new CreatedAuction(sellerAuctionService.create(userId, request.toTerms()));
    }

    @PatchMapping("/{auctionId}")
    public AuctionDetail update(@LoginMember Long userId, @PathVariable Long auctionId,
                                @Valid @RequestBody AuctionTermsRequest request) {
        sellerAuctionService.update(userId, auctionId, request.toTerms());
        return auctionQueryService.detail(auctionId);
    }

    @DeleteMapping("/{auctionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginMember Long userId, @PathVariable Long auctionId) {
        sellerAuctionService.delete(userId, auctionId);
    }

    public record CreatedAuction(Long auctionId) {
    }
}
