package com.restless.fanmeeting.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 승격 Worker의 한 번 실행 (transactions.md 10절). {@link FanMeetingEntryScheduler}가 30초마다 부른다.
 * 결제 진행 중인 행사를 모두 훑고, 행사마다 따로 처리해 하나가 실패해도 나머지는 계속한다.
 * 알림 요청은 승격이 커밋된 뒤에만 기록한다(11절).
 */
@Component
@RequiredArgsConstructor
public class FanMeetingPromotionWorker {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingPromotionWorker.class);

    private final FanMeetingPromotion promotion;
    private final NotificationRequestPort notifications;
    private final MeterRegistry meterRegistry;

    @Value("${restless.fanmeeting.worker.batch-size:100}")
    private int batchSize = 100;

    public void promoteDue() {
        Long afterId = 0L;
        while (true) {
            List<Long> eventIds = promotion.findInPayment(afterId, batchSize);
            for (Long eventId : eventIds) {
                try {
                    List<NotificationRequest> committed = promotion.promote(eventId, promotion.preview(eventId));
                    committed.forEach(notifications::request);
                } catch (RuntimeException e) {
                    log.error("FANMEETING_PROMOTION_FAILED eventId={}", eventId, e);
                    meterRegistry.counter("fanmeeting.worker.failure", "action", "promotion").increment();
                }
            }
            if (eventIds.size() < batchSize) {
                return;
            }
            afterId = eventIds.get(eventIds.size() - 1);
        }
    }
}
