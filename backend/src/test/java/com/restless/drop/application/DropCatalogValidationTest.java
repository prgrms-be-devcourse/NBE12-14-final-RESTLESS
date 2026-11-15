package com.restless.drop.application;

import com.restless.drop.application.dto.CreateDropRequest;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DropCatalogValidationTest {
    @Test
    void skuPriceMustBeAtLeastOneWon() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (long price : new long[] {-1, 0}) {
                assertThat(validator.validate(new CreateDropRequest.Variant("A", "M", price, 1)))
                        .hasSize(1);
            }
            assertThat(validator.validate(new CreateDropRequest.Variant("A", "M", 1L, 1))).isEmpty();
        }
    }

    @Test
    void missingValuesAndNullSkuAreRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new CreateDropRequest(null, " ", null, null, null, null, null)))
                    .hasSize(7);
            assertThat(validator.validate(new CreateDropRequest(1L, "상품", "설명", "url",
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Arrays.asList((CreateDropRequest.Variant) null))))
                    .isNotEmpty();
            assertThat(validator.validate(new CreateDropRequest.Variant("A", "FREE", 1L, 1))).isEmpty();
            assertThat(validator.validate(new CreateDropRequest.Variant("A".repeat(51), "FREE", null, null)))
                    .hasSize(3);
        }
    }
}
