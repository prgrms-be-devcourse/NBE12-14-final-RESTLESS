package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("대기열 조회 간격 정책 (#390)")
class DropQueuePollPolicyTest {
    private static final DropQueueSettings DEFAULT_SETTINGS =
            new DropQueueSettings(Duration.ofSeconds(40), Duration.ofSeconds(10), Duration.ofMinutes(5), 100);

    private static DropQueuePollPolicy policy(DropQueuePollProperties properties) {
        return new DropQueuePollPolicy(properties, DEFAULT_SETTINGS);
    }

    @ParameterizedTest(name = "순번 {0}이면 {1}ms")
    @CsvSource({"1,1000", "20,1000", "21,2000", "200,2000", "201,3000", "1000,3000", "1001,5000", "100000,5000"})
    @DisplayName("기본 구간 경계")
    void defaultTierBoundaries(long position, long expected) {
        assertThat(policy(new DropQueuePollProperties()).afterMs(position)).isEqualTo(expected);
    }

    @Test
    @DisplayName("상한을 쓰지 않으면 10초, heartbeat 간격, 이탈 판정 / 3 중 가장 작은 값이다")
    void defaultMaxFollowsHeartbeatSettings() {
        var settings = new DropQueueSettings(Duration.ofSeconds(3), null, Duration.ofMinutes(5), 100);
        var policy = new DropQueuePollPolicy(new DropQueuePollProperties(), settings);
        // 간격 750ms, 이탈 판정 3초 / 3 = 1초 중 작은 값
        assertThat(policy.afterMs(1)).isEqualTo(750);
        assertThat(policy.afterMs(5000)).isEqualTo(750);
    }

    @Test
    @DisplayName("구간 값이 상한보다 크면 상한으로 줄인다")
    void clampsToMax() {
        var properties = new DropQueuePollProperties();
        properties.setMaxMs(2500L);
        var policy = policy(properties);
        assertThat(policy.afterMs(201)).isEqualTo(2500);
        assertThat(policy.afterMs(5000)).isEqualTo(2500);
        assertThat(policy.afterMs(20)).isEqualTo(1000);
    }

    @Test
    @DisplayName("상한이 heartbeat 기록 간격과 같으면 받아들인다")
    void maxEqualToHeartbeatIntervalIsAccepted() {
        var properties = new DropQueuePollProperties();
        properties.setMaxMs(10_000L);
        assertThat(policy(properties).afterMs(5000)).isEqualTo(5000);
    }

    @Test
    @DisplayName("상한이 heartbeat 기록 간격보다 크면 설정 검증이 실패한다")
    void maxAboveHeartbeatIntervalFails() {
        var properties = new DropQueuePollProperties();
        properties.setMaxMs(10_001L);
        assertThatThrownBy(() -> policy(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat interval");
    }

    @Test
    @DisplayName("이탈 판정 시간 안에 3번 미만 들어오는 상한이면 설정 검증이 실패한다")
    void fewerThanThreePollsPerTimeoutFails() {
        var settings = new DropQueueSettings(Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofMinutes(5), 100);
        var properties = new DropQueuePollProperties();
        properties.setMaxMs(7000L);
        assertThatThrownBy(() -> new DropQueuePollPolicy(properties, settings))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("polls");
        properties.setMaxMs(6666L);
        assertThat(new DropQueuePollPolicy(properties, settings).afterMs(5000)).isEqualTo(5000);
    }

    @Test
    @DisplayName("구간 순서와 값이 잘못되면 설정 검증이 실패한다")
    void invalidTiersFail() {
        var notIncreasing = new DropQueuePollProperties();
        notIncreasing.setTiers(List.of(new DropQueuePollProperties.Tier(20, 1000),
                new DropQueuePollProperties.Tier(20, 2000)));
        assertThatThrownBy(() -> policy(notIncreasing)).isInstanceOf(IllegalArgumentException.class);

        var decreasing = new DropQueuePollProperties();
        decreasing.setTiers(List.of(new DropQueuePollProperties.Tier(20, 2000),
                new DropQueuePollProperties.Tier(200, 1000)));
        assertThatThrownBy(() -> policy(decreasing)).isInstanceOf(IllegalArgumentException.class);

        var zero = new DropQueuePollProperties();
        zero.setTiers(List.of(new DropQueuePollProperties.Tier(20, 0)));
        assertThatThrownBy(() -> policy(zero)).isInstanceOf(IllegalArgumentException.class);

        var shortFallback = new DropQueuePollProperties();
        shortFallback.setFallbackMs(2000);
        assertThatThrownBy(() -> policy(shortFallback)).isInstanceOf(IllegalArgumentException.class);

        var badMax = new DropQueuePollProperties();
        badMax.setMaxMs(0L);
        assertThatThrownBy(() -> policy(badMax)).isInstanceOf(IllegalArgumentException.class);
    }
}
