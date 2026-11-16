package com.restless.fanmeeting.application;

import com.restless.fanmeeting.application.FanMeetingDrawTransitions.DrawClaim;
import com.restless.fanmeeting.application.FanMeetingDrawTransitions.DrawPlan;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Draw Worker의 한 번 실행 (transactions.md 5절). {@link FanMeetingEntryScheduler}가 5초마다 부른다.
 * 추첨마다 따로 처리해 하나가 실패해도 나머지는 계속한다. 실패한 추첨은 lease가 끝난 뒤 다시 가져간다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingDrawWorker {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingDrawWorker.class);

    private final FanMeetingDrawTransitions draws;
    private final NotificationRequestPort notifications;
    private final MeterRegistry meterRegistry;

    @Value("${restless.fanmeeting.worker.batch-size:100}")
    private int batchSize = 100;

    public void drawDue() {
        for (Long drawRunId : draws.findDue(batchSize)) {
            try {
                draw(drawRunId);
            } catch (RuntimeException e) {
                log.error("FANMEETING_DRAW_FAILED drawRunId={}", drawRunId, e);
                meterRegistry.counter("fanmeeting.worker.failure", "action", "draw").increment();
            }
        }
    }

    private void draw(Long drawRunId) {
        Optional<DrawClaim> claim = draws.claim(drawRunId);
        if (claim.isEmpty()) {
            return;
        }
        DrawPlan plan = draws.compute(claim.get());
        List<NotificationRequest> committed = draws.finalizeDraw(claim.get(), plan);
        committed.forEach(notifications::request);
    }
}
