package com.restless.auction.api.dto;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.Auction.Terms;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 경매 등록·수정 요청. 수정(PATCH)도 모든 항목을 다시 보낸다.
 * 시각 규칙(시작은 현재 이후, 시작 < 종료)은 도메인에서 검증한다.
 */
public record AuctionTermsRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String itemName,
        @NotBlank @Size(max = 5000) String itemDescription,
        @Min(1) long startingPriceAmount,
        @Min(1) long minimumIncrementAmount,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Min(Auction.MIN_EXTENSION_SECONDS) @Max(Auction.MAX_EXTENSION_SECONDS) Integer extensionDurationSeconds) {

    public Terms toTerms() {
        return new Terms(title, itemName, itemDescription, startingPriceAmount, minimumIncrementAmount, startsAt, endsAt,
                extensionDurationSeconds == null ? Auction.DEFAULT_EXTENSION_SECONDS : extensionDurationSeconds);
    }
}
