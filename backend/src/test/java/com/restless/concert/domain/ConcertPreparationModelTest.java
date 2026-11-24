package com.restless.concert.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConcertPreparationModelTest {
    @Test
    void publicationPreservesFirstTimeAndLocksLayout() {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        Concert concert = Concert.createDraft(1L, 2L, "공연", "SCHEDULED", now, null, null, null, null, 3L);
        assertThat(concert.isPublished()).isFalse();
        concert.selectLayout(3L);
        concert.publish(now);
        concert.publish(now.plusSeconds(1));
        assertThat(concert.getPublishedAt()).isEqualTo(now);
        assertThatThrownBy(() -> concert.selectLayout(4L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsFreePricesAndRetainsValidSectionPrice() {
        assertThatThrownBy(() -> ConcertSectionPrice.create(1L, "A", "VIP", 0))
                .isInstanceOf(IllegalArgumentException.class);
        ConcertSectionPrice price = ConcertSectionPrice.create(1L, "A", "VIP", 1);
        assertThat(price.getPrice()).isEqualTo(1);
        assertThat(price.getConcertId()).isEqualTo(1L);
    }
}
