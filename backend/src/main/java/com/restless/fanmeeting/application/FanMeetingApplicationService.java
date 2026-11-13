package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingApplication;
import com.restless.fanmeeting.domain.FanMeetingApplicationStatus;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository.ApplicationState;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응모, 응모 취소, 내 응모 상태 (transactions.md 3·4절).
 * 잠금 순서: Event FOR SHARE → Application. Event 행은 고치지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingApplicationService {

    private final FanMeetingEventRepository events;
    private final FanMeetingApplicationRepository applications;
    private final Clock clock;

    /** 응모 1회 시도 = 트랜잭션 1개. 없으면 INSERT, 취소 상태면 같은 행을 다시 APPLIED로. */
    @Transactional(timeout = 5)
    public FanMeetingApplication apply(Long userId, Long eventId) {
        Instant now = clock.instant();
        lockEventForShare(eventId).requireApplicationWindow(now);

        Optional<ApplicationState> state = applications.findStateByEventIdAndUserId(eventId, userId);
        if (state.isEmpty()) {
            return applications.save(FanMeetingApplication.apply(eventId, userId, now));
        }
        if (state.get().getStatus() == FanMeetingApplicationStatus.APPLIED) {
            throw new BusinessException(ErrorCode.ALREADY_APPLIED);
        }
        FanMeetingApplication application = lockApplication(state.get().getId());
        if (application.isApplied()) {
            throw new BusinessException(ErrorCode.ALREADY_APPLIED);
        }
        application.reapply(now);
        return application;
    }

    /** 응모 취소. APPLIED만 WITHDRAWN으로 바꾼다. */
    @Transactional(timeout = 5)
    public void cancel(Long userId, Long eventId) {
        Instant now = clock.instant();
        lockEventForShare(eventId).requireApplicationWindow(now);

        ApplicationState state = applications.findStateByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_ACTIVE));
        FanMeetingApplication application = lockApplication(state.getId());
        if (!application.isApplied()) {
            throw new BusinessException(ErrorCode.APPLICATION_NOT_ACTIVE);
        }
        application.withdraw(now);
    }

    /** 내 응모(잠금 없음). 응모한 적 없으면 빈 값, 행사가 없으면 FAN_MEETING_NOT_FOUND. */
    @Transactional(readOnly = true)
    public Optional<FanMeetingApplication> findMine(Long userId, Long eventId) {
        if (!events.existsById(eventId)) {
            throw new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND);
        }
        return applications.findByEventIdAndUserId(eventId, userId);
    }

    private FanMeetingEvent lockEventForShare(Long eventId) {
        return events.findByIdForShare(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
    }

    private FanMeetingApplication lockApplication(Long applicationId) {
        return applications.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new IllegalStateException("응모 행이 사라졌습니다: " + applicationId));
    }
}
