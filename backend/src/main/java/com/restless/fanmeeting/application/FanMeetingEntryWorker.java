package com.restless.fanmeeting.application;

import com.restless.fanmeeting.application.FanMeetingEntryTransitions.OpenResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Open / Close Worker의 한 번 실행 (transactions.md 2절). {@link FanMeetingEntryScheduler}가 1초마다 부른다.
 * 행사마다 따로 처리해 하나가 실패해도 나머지는 계속한다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingEntryWorker {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingEntryWorker.class);

    private final FanMeetingEntryTransitions transitions;
    private final MeterRegistry meterRegistry;

    /** 이 서버에서 이미 OPEN_MISSED를 알린 행사. 운영자가 처리할 때까지 매초 같은 로그가 쌓이지 않게 한다. */
    private final Set<Long> missedReported = ConcurrentHashMap.newKeySet();

    @Value("${restless.fanmeeting.worker.batch-size:100}")
    private int batchSize = 100;

    public void openDue() {
        each("open", transitions.findDueToOpen(batchSize), transitions::open);
        for (Long eventId : transitions.findOpenMissed(batchSize)) {
            if (transitions.open(eventId) == OpenResult.MISSED && missedReported.add(eventId)) {
                log.error("FANMEETING_OPEN_MISSED eventId={} 마감 시각까지 열지 못했습니다. 운영자 처리가 필요합니다.", eventId);
            }
        }
    }

    public void closeDue() {
        each("close", transitions.findDueToClose(batchSize), transitions::close);
    }

    private void each(String action, List<Long> eventIds, Consumer<Long> task) {
        for (Long eventId : eventIds) {
            try {
                task.accept(eventId);
            } catch (RuntimeException e) {
                log.error("FanMeeting {} failed eventId={}", action, eventId, e);
                meterRegistry.counter("fanmeeting.worker.failure", "action", action).increment();
            }
        }
    }
}
