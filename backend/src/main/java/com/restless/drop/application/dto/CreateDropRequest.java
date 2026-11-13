package com.restless.drop.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public record CreateDropRequest(
        @NotNull @Positive Long concertId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String description,
        @NotBlank @Size(max = 500) String imageUrl,
        @NotNull Instant opensAt,
        @NotNull Instant closesAt,
        @NotEmpty List<@NotNull @Valid Variant> variants) {
    public record Variant(
            @NotBlank @Size(max = 50) String skuCode,
            @NotBlank @Size(max = 100) String optionName,
            @NotNull @Min(1) Long priceAmount,
            @NotNull @Min(1) Integer totalQuantity) {}
}
