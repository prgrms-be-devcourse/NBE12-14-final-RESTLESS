package com.restless.drop.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 결제 정산 통합 테스트가 함께 쓰는 설정. 같은 설정 클래스를 import해서 Spring 테스트 context 하나를 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class DropPaymentTestClockConfig {
    @Bean @Primary
    MutableClock paymentTestClock() { return new MutableClock(); }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
