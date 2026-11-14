package com.restless.fanmeeting.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 만료 Worker의 한 번 실행 (transactions.md 8절). {@link FanMeetingEntryScheduler}가 1분마다 부른다.
 * 주문마다 따로 처리해 하나가 실패해도 나머지는 계속한다. 실패한 주문은 다음 회차에 다시 후보가 된다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingExpiryWorker.class);

    private final FanMeetingOrderExpiry expiry;
    private final MeterRegistry meterRegistry;

    @Value("${restless.fanmeeting.worker.batch-size:100}")
    private int batchSize = 100;

    /** 결제를 준비하지 않았거나 실패 뒤 다시 결제하지 않은 채 기한이 지난 주문을 만료한다. */
    public void expireDue() {
        each("expire", expiry.findDueToExpire(batchSize), expiry::expire);
    }

    /** 30분 넘게 결제 결과를 모르는 보류 주문을 한 번씩 경고한다. */
    public void warnStaleReviews() {
        each("review-warning", expiry.findReviewsToWarn(batchSize), expiry::warnStaleReview);
    }

    private void each(String action, List<Long> orderIds, Consumer<Long> task) {
        for (Long orderId : orderIds) {
            try {
                task.accept(orderId);
            } catch (RuntimeException e) {
                log.error("FanMeeting {} failed orderId={}", action, orderId, e);
                meterRegistry.counter("fanmeeting.worker.failure", "action", action).increment();
            }
        }
    }
}
