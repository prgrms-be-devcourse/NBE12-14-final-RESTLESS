package com.restless.concert.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConcertPreparationModelTest {
    @Test
    void rejectsFreePricesAndRetainsValidSectionPrice() {
        assertThatThrownBy(() -> ConcertSectionPrice.create(1L, "A", "VIP", 0))
                .isInstanceOf(IllegalArgumentException.class);
        ConcertSectionPrice price = ConcertSectionPrice.create(1L, "A", "VIP", 1);
        assertThat(price.getPrice()).isEqualTo(1);
        assertThat(price.getConcertId()).isEqualTo(1L);
    }
}
