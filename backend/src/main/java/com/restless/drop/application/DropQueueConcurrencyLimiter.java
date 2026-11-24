package com.restless.drop.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 한 대에서 대기열 진입과 상태 조회를 동시에 처리하는 수의 상한 (2026-11-24 사용자 위임 결정, #390).
 * 넘으면 기다리지 않고 바로 거절해, 클라이언트가 이미 포기한 요청에 스레드와 커넥션을 쓰지 않게 한다.
 * 재고 점유(TX1), 결제, 결제 확정에는 쓰지 않는다.
 */
@Component
public class DropQueueConcurrencyLimiter {
    public enum Kind { ENTRY, STATUS }

    private final Semaphore entry;
    private final Semaphore status;
    private final int retryAfterSeconds;
    private final MeterRegistry registry;

    public DropQueueConcurrencyLimiter(
            @Value("${restless.drop.queue.concurrency.entry-max:8}") int entryMax,
            @Value("${restless.drop.queue.concurrency.status-max:30}") int statusMax,
            @Value("${restless.drop.queue.concurrency.retry-after-seconds:1}") int retryAfterSeconds,
            MeterRegistry registry) {
        if (entryMax < 1 || statusMax < 1 || retryAfterSeconds < 1)
            throw new IllegalArgumentException("Queue concurrency limits and retry-after must be positive");
        this.entry = new Semaphore(entryMax);
        this.status = new Semaphore(statusMax);
        this.retryAfterSeconds = retryAfterSeconds;
        this.registry = registry;
    }

    /** 허용치가 남아 있으면 하나 가져가고 true. 없으면 기다리지 않고 false. */
    public boolean tryAcquire(Kind kind) {
        if (semaphore(kind).tryAcquire()) return true;
        registry.counter("drop.queue.busy", "kind", kind.name().toLowerCase()).increment();
        return false;
    }

    public void release(Kind kind) {
        semaphore(kind).release();
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int available(Kind kind) {
        return semaphore(kind).availablePermits();
    }

    private Semaphore semaphore(Kind kind) {
        return kind == Kind.ENTRY ? entry : status;
    }
}
