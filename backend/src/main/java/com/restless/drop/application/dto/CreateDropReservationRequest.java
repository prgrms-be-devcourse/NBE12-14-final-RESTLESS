package com.restless.drop.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateDropReservationRequest(
        @NotNull @Positive Long variantId,
        @NotBlank @Size(max = 50) String recipient,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(max = 255) String address) {
}
