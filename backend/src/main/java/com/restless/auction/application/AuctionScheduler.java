package com.restless.auction.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1초마다 시작할 경매를 열고, 끝난 경매를 닫는다.
 *
 * <p>입찰 가능 여부는 상태가 아니라 시각으로 판단하므로, 스케줄러가 늦어도 종료 시각 뒤 입찰은 거절된다.
 * 서버가 재시작되면 다음 실행에서 밀린 경매를 처리한다. 서버가 여러 대여도 잠금과 재확인 덕분에 결과는 하나다.
 *
 * <p>테스트에서는 {@code restless.auction.scheduler.enabled=false}로 끈다 (build.gradle).
 */
@Component
@ConditionalOnProperty(name = "restless.auction.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuctionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionScheduler.class);

    private final AuctionCloseService closeService;
    private final MeterRegistry meterRegistry;

    @Value("${restless.auction.scheduler.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${restless.auction.scheduler.fixed-delay-ms:1000}")
    public void run() {
        process("open", closeService.findDueToOpen(batchSize), closeService::open);
        process("close", closeService.findDueToClose(batchSize), closeService::close);
    }

    /** 경매마다 따로 처리한다. 하나가 실패해도 나머지는 계속하고, 실패는 로그와 메트릭으로 남긴다. */
    private void process(String action, List<Long> auctionIds, Function<Long, Boolean> task) {
        for (Long auctionId : auctionIds) {
            try {
                task.apply(auctionId);
            } catch (RuntimeException e) {
                log.error("Auction {} failed auctionId={}", action, auctionId, e);
                meterRegistry.counter("auction.scheduler.failure", "action", action).increment();
            }
        }
    }
}
