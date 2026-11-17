package com.restless.fanmeeting.application;

import com.restless.fanmeeting.application.FanMeetingOrderExpiry.Cursor;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 만료 Worker의 한 번 실행 (transactions.md 8절). {@link FanMeetingEntryScheduler}가 1분마다 부른다.
 * 주문마다 따로 처리해 하나가 실패해도 나머지는 계속한다. 실패한 주문은 다음 회차에 다시 후보가 된다.
 * 한 회차에서 대상이 없어질 때까지 묶음(batch-size)을 반복한다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingExpiryWorker {

    /** 한 회차에 처리하는 최대 묶음 수. 넘으면 다음 회차가 이어 간다. */
    public static final int MAX_ROUNDS = 100;

    private static final Logger log = LoggerFactory.getLogger(FanMeetingExpiryWorker.class);

    private final FanMeetingOrderExpiry expiry;
    private final MeterRegistry meterRegistry;

    @Value("${restless.fanmeeting.worker.batch-size:100}")
    private int batchSize = 100;

    /** 결제를 준비하지 않았거나 실패 뒤 다시 결제하지 않은 채 기한이 지난 주문을 만료한다. */
    public void expireDue() {
        drain("expire", expiry::findDueToExpire, expiry::expire);
    }

    /** 30분 넘게 결제 결과를 모르는 보류 주문을 한 번씩 경고한다. */
    public void warnStaleReviews() {
        drain("review-warning", expiry::findReviewsToWarn, expiry::warnStaleReview);
    }

    /**
     * 대상이 없어질 때까지 묶음을 반복한다. 당첨자는 주문이 같은 시각에 발급돼 기한도 같이 끝나므로,
     * 한 회차에 한 묶음만 처리하면 만료와 그 뒤의 승격이 밀린다.
     *
     * <p>조회한 마지막 시각·ID 뒤부터 이어 읽는다. 실패 건은 다음 회차에서 다시 시도한다.
     * 묶음이 덜 찼거나 {@link #MAX_ROUNDS}묶음을 채웠으면 멈춘다.
     */
    private void drain(String action, BiFunction<Cursor, Integer, List<Cursor>> finder, Consumer<Long> task) {
        Cursor after = null;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Cursor> candidates = finder.apply(after, batchSize);
            for (Cursor candidate : candidates) {
                Long orderId = candidate.orderId();
                try {
                    task.accept(orderId);
                } catch (RuntimeException e) {
                    log.error("FanMeeting {} failed orderId={}", action, orderId, e);
                    meterRegistry.counter("fanmeeting.worker.failure", "action", action).increment();
                }
            }
            if (candidates.size() < batchSize) {
                return;
            }
            after = candidates.getLast();
        }
    }
}
