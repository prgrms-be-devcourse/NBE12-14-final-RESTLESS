package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.drop.domain.Drop;
import com.restless.drop.infrastructure.DropInventoryRepository;
import com.restless.drop.infrastructure.DropRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("드롭 단위 품절 표시 (#481)")
class DropSoldOutCacheTest {
    private static final long DROP = 7L;
    private static final Instant START = Instant.parse("2026-11-26T05:00:00Z");
    private final DropInventoryRepository inventories = mock(DropInventoryRepository.class);
    private final DropRepository drops = mock(DropRepository.class);
    private final Drop drop = mock(Drop.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(START);
    private final DropSoldOutCache cache =
            new DropSoldOutCache(inventories, drops, clock, registry, Duration.ofSeconds(1));

    {
        when(drops.findById(DROP)).thenReturn(Optional.of(drop));
        when(drop.isOnSale(any())).thenAnswer(i -> ((Instant) i.getArgument(0)).isBefore(START.plusSeconds(3600)));
        when(drop.getClosesAt()).thenReturn(START.plusSeconds(3600));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private void stock(long activeSkus, long available) {
        when(inventories.activeStockOfDrop(DROP)).thenReturn(List.<Object[]>of(new Object[] {activeSkus, available}));
    }

    @Test
    @DisplayName("확인한 적 없는 드롭은 거절하지 않고 DB도 읽지 않는다")
    void unknownDropIsNotRejected() {
        assertThat(cache.rejects(DROP)).isFalse();
        verify(inventories, times(0)).activeStockOfDrop(DROP);
    }

    @Test
    @DisplayName("판매 중 SKU가 모두 품절이면 유효 시간 동안 DB를 다시 읽지 않고 거절한다")
    void soldOutDropIsRejectedWithinTtl() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        assertThat(cache.rejects(DROP)).isTrue();
        clock.advance(Duration.ofMillis(999));
        assertThat(cache.rejects(DROP)).isTrue();
        verify(inventories, times(1)).activeStockOfDrop(DROP);
        assertThat(registry.counter("drop.queue.soldout.fast").count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("일부 SKU에 재고가 있으면 거절하지 않고, 유효 시간 안의 다음 409는 DB를 다시 읽지 않는다")
    void partlySoldOutDropIsNotRejected() {
        stock(3, 5);
        cache.observeVariantSoldOut(DROP);
        cache.observeVariantSoldOut(DROP);
        assertThat(cache.rejects(DROP)).isFalse();
        verify(inventories, times(1)).activeStockOfDrop(DROP);
        clock.advance(Duration.ofSeconds(1));
        cache.observeVariantSoldOut(DROP);
        verify(inventories, times(2)).activeStockOfDrop(DROP);
    }

    @Test
    @DisplayName("판매 중 SKU가 없는 드롭은 품절로 표시하지 않는다")
    void dropWithoutActiveSkuIsNotMarked() {
        stock(0, 0);
        cache.observeVariantSoldOut(DROP);
        assertThat(cache.rejects(DROP)).isFalse();
    }

    @Test
    @DisplayName("유효 시간이 지나면 한 요청이 다시 읽고, 여전히 품절이면 거절을 이어 간다")
    void expiredMarkIsRecheckedAndKept() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.rejects(DROP)).isTrue();
        verify(inventories, times(2)).activeStockOfDrop(DROP);
        clock.advance(Duration.ofMillis(500));
        assertThat(cache.rejects(DROP)).isTrue();
        verify(inventories, times(2)).activeStockOfDrop(DROP);
        assertThat(registry.counter("drop.queue.soldout.recheck").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("다시 읽어 재고가 돌아왔으면 표시를 끄고 기존 경로로 보낸다")
    void restockedDropIsReleased() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(1));
        stock(3, 1);
        assertThat(cache.rejects(DROP)).isFalse();
        assertThat(cache.rejects(DROP)).isFalse();
        verify(inventories, times(2)).activeStockOfDrop(DROP);
    }

    @Test
    @DisplayName("다시 읽다가 실패하면 표시를 지우고 기존 경로로 보낸다")
    void recheckFailureFallsBackToNormalPath() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(1));
        when(inventories.activeStockOfDrop(DROP)).thenThrow(new IllegalStateException("db down"));
        assertThat(cache.rejects(DROP)).isFalse();
        assertThat(cache.rejects(DROP)).isFalse();
        assertThat(registry.counter("drop.queue.soldout.recheck.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한 요청이 다시 읽는 동안 들어온 요청은 DB를 읽지 않고 거절된다")
    void concurrentRequestsDuringRecheckAreRejectedWithoutQuery() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(1));
        boolean[] nested = new boolean[1];
        when(inventories.activeStockOfDrop(DROP)).thenAnswer(invocation -> {
            nested[0] = cache.rejects(DROP);
            return List.<Object[]>of(new Object[] {3L, 0L});
        });
        assertThat(cache.rejects(DROP)).isTrue();
        assertThat(nested[0]).isTrue();
        verify(inventories, times(2)).activeStockOfDrop(DROP);
    }

    @Test
    @DisplayName("판매 종료 시각이 지나면 유효 시간 안이어도 거절하지 않아 기존 경로가 판매 기간 오류를 준다 (#481 검증 D1)")
    void saleEndReleasesImmediately() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(3600));
        assertThat(cache.rejects(DROP)).isFalse();
        verify(inventories, times(1)).activeStockOfDrop(DROP);
    }

    @Test
    @DisplayName("판매 중이 아닌 드롭은 재고가 0이어도 표시하지 않고, 재확인 때 판매 중이 아니면 표시를 끈다")
    void notOnSaleDropIsNotMarked() {
        stock(3, 0);
        cache.observeVariantSoldOut(DROP);
        clock.advance(Duration.ofSeconds(1));
        doReturn(false).when(drop).isOnSale(any());
        assertThat(cache.rejects(DROP)).isFalse();
        assertThat(cache.rejects(DROP)).isFalse();
        when(drops.findById(8L)).thenReturn(Optional.empty());
        cache.observeVariantSoldOut(8L);
        assertThat(cache.rejects(8L)).isFalse();
    }

    @Test
    @DisplayName("표시를 켜는 읽기가 실패해도 예외를 던지지 않는다. 진입의 409는 그대로 나간다")
    void markFailureDoesNotThrow() {
        when(inventories.activeStockOfDrop(DROP)).thenThrow(new IllegalStateException("db down"));
        cache.observeVariantSoldOut(DROP);
        assertThat(cache.rejects(DROP)).isFalse();
        assertThat(registry.counter("drop.queue.soldout.mark.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("유효 시간 0이면 표시를 쓰지 않는다")
    void zeroTtlDisables() {
        var disabled = new DropSoldOutCache(inventories, drops, clock, registry, Duration.ZERO);
        stock(3, 0);
        disabled.observeVariantSoldOut(DROP);
        assertThat(disabled.rejects(DROP)).isFalse();
        verify(inventories, times(0)).activeStockOfDrop(DROP);
    }
}
