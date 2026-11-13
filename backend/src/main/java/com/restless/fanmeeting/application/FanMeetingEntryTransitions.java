package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingDrawRun;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import com.restless.fanmeeting.infrastructure.FanMeetingDrawRunRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응모 기간 열기·마감 (transactions.md 2절). 행사 1건 = 트랜잭션 1개.
 * Event FOR UPDATE 뒤 상태와 시각을 다시 확인하므로, 서버가 여러 대여도 결과는 하나다.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingEntryTransitions {

    /** 열기 결과. */
    public enum OpenResult { OPENED, MISSED, SKIPPED }

    private static final SecureRandom SEED_RANDOM = new SecureRandom();

    private final FanMeetingEventRepository events;
    private final FanMeetingDrawRunRepository drawRuns;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> findDueToOpen(int limit) {
        return events.findIdsToOpen(FanMeetingStatus.SCHEDULED, clock.instant(), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<Long> findOpenMissed(int limit) {
        return events.findIdsOpenMissed(FanMeetingStatus.SCHEDULED, clock.instant(), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<Long> findDueToClose(int limit) {
        return events.findIdsToClose(FanMeetingStatus.OPEN, clock.instant(), PageRequest.of(0, limit));
    }

    /** SCHEDULED → OPEN. 마감 시각까지 지났으면 열지 않고 MISSED. */
    @Transactional(timeout = 5)
    public OpenResult open(Long eventId) {
        Instant now = clock.instant();
        FanMeetingEvent event = events.findByIdForUpdate(eventId).orElse(null);
        if (event == null) {
            return OpenResult.SKIPPED;
        }
        if (event.isOpenMissed(now)) {
            return OpenResult.MISSED;
        }
        if (!event.canOpen(now)) {
            return OpenResult.SKIPPED;
        }
        event.open(now);
        return OpenResult.OPENED;
    }

    /** OPEN → CLOSED, 같은 트랜잭션에서 추첨 실행(PENDING, 새 seed)을 만든다. 닫았으면 true. */
    @Transactional(timeout = 5)
    public boolean close(Long eventId) {
        Instant now = clock.instant();
        FanMeetingEvent event = events.findByIdForUpdate(eventId).orElse(null);
        if (event == null || !event.canClose(now)) {
            return false;
        }
        event.close(now);
        drawRuns.save(FanMeetingDrawRun.pending(eventId, newSeed(), now));
        return true;
    }

    private static byte[] newSeed() {
        byte[] seed = new byte[FanMeetingDrawRun.SEED_BYTES];
        SEED_RANDOM.nextBytes(seed);
        return seed;
    }
}
