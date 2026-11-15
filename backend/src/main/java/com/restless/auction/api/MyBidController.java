package com.restless.auction.api;

import com.restless.auction.application.MyBidQueryService;
import com.restless.auction.application.MyBidQueryService.MyBidPage;
import com.restless.auction.domain.BidStatus;
import com.restless.auth.LoginMember;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 입찰. 내가 입찰한 경매마다 마지막 입찰과 상태(HIGHEST, OUTBID, WON). */
@RestController
@RequiredArgsConstructor
public class MyBidController {

    private final MyBidQueryService myBidQueryService;

    /** status를 빼면 전체. page는 0부터, size는 1~100. 최근에 입찰한 경매부터. */
    @GetMapping("/api/me/bids")
    public MyBidPage myBids(@LoginMember Long userId,
                            @RequestParam(required = false) BidStatus status,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > AuctionController.MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        return myBidQueryService.myBids(userId, status, page, size);
    }
}
