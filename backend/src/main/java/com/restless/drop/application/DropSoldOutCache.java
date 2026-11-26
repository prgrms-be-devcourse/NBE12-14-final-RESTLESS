package com.restless.drop.application;

import com.restless.drop.infrastructure.DropInventoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 한 대가 기억하는 드롭 단위 품절 표시 (#481, 2026-11-26 사용자 결정).
 *
 * <p>판매 중 SKU가 모두 {@code available = 0}인 드롭만 품절로 표시한다. 표시가 켜진 드롭의 진입은 필터가 Security와
 * MVC 앞에서 409 {@code DROP_SOLD_OUT}으로 끝낸다. 표시는 DB에서 읽은 재고 합으로만 켜고, 유효 시간(기본 1초)이
 * 지나면 요청 한 건만 DB를 다시 읽는다. 그동안 나머지 요청은 409를 계속 받는다. 재고가 있으면 표시를 끈다.
 *
 * <p>거절만 한다. 재고 점유와 입장 허가는 이 표시를 보지 않으므로 초과 판매(INV-01, 02)와 무관하다. 재고가 돌아온 뒤
 * 새 진입이 409를 받는 시간은 최대 유효 시간 + 재확인 시간이다.
 */
@Slf4j
@Component
public class DropSoldOutCache {
    /** soldOut: 마지막 확인 결과. checkedAt: 확인 시각. recheckingSince: 재확인을 맡은 요청이 시작한 시각, 없으면 null. */
    private record State(boolean soldOut, Instant checkedAt, Instant recheckingSince) { }

    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    private final DropInventoryRepository inventories;
    private final Clock clock;
    private final Duration ttl;
    private final Counter fast;
    private final Counter rechecks;
    private final MeterRegistry registry;

    public DropSoldOutCache(DropInventoryRepository inventories, Clock clock, MeterRegistry registry,
            @Value("${restless.drop.queue.sold-out-cache-ttl:1s}") Duration ttl) {
        if (ttl.isNegative()) throw new IllegalArgumentException("sold-out-cache-ttl must not be negative");
        this.inventories = inventories;
        this.clock = clock;
        this.ttl = ttl;
        this.registry = registry;
        this.fast = registry.counter("drop.queue.soldout.fast");
        this.rechecks = registry.counter("drop.queue.soldout.recheck");
    }

    /**
     * 필터가 부른다. true면 이 진입을 지금 409로 끝낸다. 유효 시간이 지난 표시는 요청 한 건이 여기서 DB를 다시 읽는다.
     * 다시 읽다가 실패하면 표시를 지우고 false를 돌려 기존 경로로 보낸다.
     */
    public boolean rejects(Long dropId) {
        if (ttl.isZero()) return false;
        State state = states.get(dropId);
        if (state == null || !state.soldOut()) return false;
        Instant now = clock.instant();
        if (now.isBefore(state.checkedAt().plus(ttl))) return reject();
        if (state.recheckingSince() != null && now.isBefore(state.recheckingSince().plus(ttl))) return reject();
        // 재확인을 맡는다. 다른 요청이 먼저 바꿨으면 그 결과를 따른다.
        if (!states.replace(dropId, state, new State(true, state.checkedAt(), now))) return rejects(dropId);
        rechecks.increment();
        try {
            if (record(dropId)) return reject();
            return false;
        } catch (RuntimeException e) {
            states.remove(dropId);
            log.warn("Sold-out recheck failed for drop {}; falling back to the normal path", dropId, e);
            registry.counter("drop.queue.soldout.recheck.failed").increment();
            return false;
        }
    }

    /**
     * 진입이 SKU 품절로 409를 낼 때 부른다. 유효 시간 안에 확인한 결과가 있으면 DB를 읽지 않는다.
     * 드롭 전체가 품절이면 표시를 켠다. 일부 SKU만 품절이어도 결과를 기억해 다음 409마다 다시 읽지 않는다.
     */
    public void observeVariantSoldOut(Long dropId) {
        if (ttl.isZero()) return;
        State state = states.get(dropId);
        if (state != null && clock.instant().isBefore(state.checkedAt().plus(ttl))) return;
        record(dropId);
    }

    /** DB에서 드롭의 판매 중 SKU 재고 합을 읽어 표시를 갱신하고, 품절이면 true. */
    private boolean record(Long dropId) {
        Object[] row = inventories.activeStockOfDrop(dropId).get(0);
        long activeSkus = ((Number) row[0]).longValue();
        long available = ((Number) row[1]).longValue();
        boolean soldOut = activeSkus > 0 && available == 0;
        states.put(dropId, new State(soldOut, clock.instant(), null));
        return soldOut;
    }

    private boolean reject() {
        fast.increment();
        return true;
    }
}
