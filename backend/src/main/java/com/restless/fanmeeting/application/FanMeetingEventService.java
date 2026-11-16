package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingSettings;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행사 생성, 상세 조회. */
@Service
@RequiredArgsConstructor
public class FanMeetingEventService {

    private final FanMeetingEventRepository events;
    private final Clock clock;

    /** 행사를 만들고 ID를 돌려준다. 만든 회원이 그 행사의 주최자가 된다. */
    @Transactional
    public Long create(Long organizerId, FanMeetingSettings settings) {
        return events.save(FanMeetingEvent.create(settings, organizerId, clock.instant())).getId();
    }

    /** 행사 상세. 없으면 FAN_MEETING_NOT_FOUND. */
    @Transactional(readOnly = true)
    public FanMeetingEvent get(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
    }
}
