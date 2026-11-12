package com.restless.auction.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 입찰 요청. 금액은 사용자가 직접 입력한다. */
public record BidRequest(@NotNull @Min(1) Long amount) {
}
