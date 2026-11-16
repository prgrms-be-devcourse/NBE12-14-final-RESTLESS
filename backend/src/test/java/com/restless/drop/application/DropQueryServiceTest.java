package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.domain.Drop;
import com.restless.drop.infrastructure.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropQueryServiceTest {
    @Test
    void queryUsesInjectedClockAndHidesUnpublishedDetail() {
        var drops = mock(DropRepository.class);
        var variants = mock(DropVariantRepository.class);
        var inventories = mock(DropInventoryRepository.class);
        var start = Instant.parse("2026-11-20T03:00:00Z");
        var clock = Clock.fixed(start, ZoneOffset.UTC);
        var service = new DropQueryService(drops, variants, inventories, clock);
        var drop = Drop.draft(1L, "상품", "설명", "url", start, start.plusSeconds(60), start);
        when(drops.findById(1L)).thenReturn(Optional.of(drop));
        assertThatThrownBy(() -> service.getDetail(1L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        drop.publish();
        when(variants.findAllByDropIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(inventories.findAllById(List.of())).thenReturn(List.of());
        assertThat(service.getDetail(1L).saleStatus()).isEqualTo("ON_SALE");
    }
}
