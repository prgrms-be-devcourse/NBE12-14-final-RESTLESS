package com.restless.drop.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 삭제된 드롭 환불을 핵심 판매 시간대 밖인 새벽에 돌린다 (#430). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "restless.drop.cancel-refund.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DropCancelRefundScheduler {
    private final DropCancelRefundJob job;

    @Scheduled(cron = "${restless.drop.cancel-refund.cron:0 30 0 * * *}", zone = "Asia/Seoul")
    public void run() { job.runOnce(); }
}
