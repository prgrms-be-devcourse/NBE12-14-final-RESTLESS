package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("대기열 동시 처리 상한 (#390)")
class DropQueueConcurrencyLimiterTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private DropQueueConcurrencyLimiter limiter(int entry, int status) {
        return new DropQueueConcurrencyLimiter(entry, status, 1, registry);
    }

    @Test
    @DisplayName("상한까지 받고 넘으면 기다리지 않고 거절한다")
    void rejectsAboveLimit() {
        var limiter = limiter(2, 3);
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isFalse();
        assertThat(registry.counter("drop.queue.busy", "kind", "status").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("진입과 조회는 서로의 허용치를 쓰지 않는다")
    void kindsAreIndependent() {
        var limiter = limiter(1, 1);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isFalse();
        assertThat(limiter.tryAcquire(Kind.STATUS)).isTrue();
    }

    @Test
    @DisplayName("끝난 요청의 허용치는 다시 받을 수 있다")
    void releaseRestoresPermit() {
        var limiter = limiter(1, 1);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isFalse();
        limiter.release(Kind.ENTRY);
        assertThat(limiter.tryAcquire(Kind.ENTRY)).isTrue();
    }

    @Test
    @DisplayName("상한이나 Retry-After가 1 미만이면 설정 검증이 실패한다")
    void invalidSettingsFail() {
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(0, 1, 1, registry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 0, 1, registry)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropQueueConcurrencyLimiter(1, 1, 0, registry)).isInstanceOf(IllegalArgumentException.class);
    }
}
