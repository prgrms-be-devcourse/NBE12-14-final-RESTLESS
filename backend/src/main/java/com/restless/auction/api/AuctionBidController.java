package com.restless.auction.api;

import com.restless.auction.api.dto.BidRequest;
import com.restless.auction.application.BidService;
import com.restless.auction.application.BidService.BidResult;
import com.restless.auth.LoginMember;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입찰. 같은 회원이 같은 Idempotency-Key로 다시 보내면 처음 입찰을 현재 상태와 함께 돌려준다 (201 동일).
 */
@RestController
@RequiredArgsConstructor
public class AuctionBidController {

    static final int MAX_KEY_LENGTH = 100;

    private final BidService bidService;

    @PostMapping("/api/auctions/{auctionId}/bids")
    @ResponseStatus(HttpStatus.CREATED)
    public BidResult bid(@LoginMember Long userId, @PathVariable Long auctionId,
                         @RequestHeader("Idempotency-Key") String requestKey,
                         @Valid @RequestBody BidRequest request) {
        if (requestKey.isBlank() || requestKey.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 1~100자여야 합니다.");
        }
        return bidService.place(userId, auctionId, request.amount(), requestKey);
    }
}
