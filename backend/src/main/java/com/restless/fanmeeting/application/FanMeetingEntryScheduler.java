package com.restless.fanmeeting.application;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 1초마다 응모 기간을 열고 닫는다. 테스트에서는 {@code restless.fanmeeting.worker.enabled=false}로 끈다(build.gradle).
 * 스케줄링 켜기를 경매 설정에 기대지 않으려고 여기서 직접 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "restless.fanmeeting.worker.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class FanMeetingEntryScheduler {

    private final FanMeetingEntryWorker worker;

    @Scheduled(fixedDelayString = "${restless.fanmeeting.worker.fixed-delay-ms:1000}")
    public void run() {
        worker.openDue();
        worker.closeDue();
    }
}
