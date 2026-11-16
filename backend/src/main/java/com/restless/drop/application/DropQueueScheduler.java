package com.restless.drop.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "restless.drop.queue.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class DropQueueScheduler {
    private final DropQueueAdmissionJob job;
    @Scheduled(fixedDelayString = "${restless.drop.queue.scheduler.fixed-delay-ms:1000}")
    public void tick() { job.runOnce(); }
}
