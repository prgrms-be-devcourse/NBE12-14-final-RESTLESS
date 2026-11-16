package com.restless.drop.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "restless.drop.expiry.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DropReservationExpiryScheduler {
    private final DropReservationExpiryJob job;

    @Scheduled(fixedDelayString = "${restless.drop.expiry.fixed-delay-ms:5000}")
    public void run() { job.runOnce(); }
}
