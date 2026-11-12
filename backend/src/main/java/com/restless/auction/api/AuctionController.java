package com.restless.auction.api;

import com.restless.auction.application.AuctionQueryService;
import com.restless.auction.application.AuctionQueryService.AuctionDetail;
import com.restless.auction.application.AuctionQueryService.AuctionPage;
import com.restless.auction.domain.AuctionStatus;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 경매 목록, 상세 조회. */
@RestController
@RequestMapping("/api/auctions")
@RequiredArgsConstructor
public class AuctionController {

    static final int MAX_PAGE_SIZE = 100;

    private final AuctionQueryService auctionQueryService;

    /** status를 빼면 전체. page는 0부터, size는 1~100. */
    @GetMapping
    public AuctionPage list(@RequestParam(required = false) AuctionStatus status,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        return auctionQueryService.list(status, page, size);
    }

    @GetMapping("/{auctionId}")
    public AuctionDetail detail(@PathVariable Long auctionId) {
        return auctionQueryService.detail(auctionId);
    }
}
