package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingSettings;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 목록·상세 조회와 주최자 행사의 생성·오픈 전 수정·취소. */
@Service
@RequiredArgsConstructor
public class FanMeetingEventService {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingEventService.class);

    private final FanMeetingEventRepository events;
    private final Clock clock;
    private final MemberService members;

    @Transactional(timeout = 5)
    public FanMeetingEvent update(Long userId, Long eventId, FanMeetingSettings settings) {
        FanMeetingEvent event = lockOwned(userId, eventId);
        event.update(settings, clock.instant());
        return event;
    }

    @Transactional(timeout = 5)
    public void cancel(Long userId, Long eventId) {
        FanMeetingEvent event = lockOwned(userId, eventId);
        if (event.cancel(clock.instant())) {
            log.info("FANMEETING_CANCELLED eventId={} actorId={} reason=BEFORE_OPEN", eventId, userId);
        }
    }

    private FanMeetingEvent lockOwned(Long userId, Long eventId) {
        boolean admin = members.getActive(userId).getRole() == MemberRole.ADMIN;
        if (!events.existsById(eventId)) {
            throw new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND);
        }
        FanMeetingEvent event = events.findByIdForUpdate(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
        if (!event.getOrganizerId().equals(userId) && !admin) {
            throw new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND);
        }
        return event;
    }

    /** 행사를 만들고 ID를 돌려준다. 만든 회원이 그 행사의 주최자가 된다. */
    @Transactional
    public Long create(Long organizerId, FanMeetingSettings settings) {
        return events.save(FanMeetingEvent.create(settings, organizerId, clock.instant())).getId();
    }

    @Transactional(readOnly = true)
    public Page<FanMeetingEvent> list(
            FanMeetingStatus status, int page, int size) {
        return events.findPublic(status, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "id")));
    }

    /** 행사 상세. 없으면 FAN_MEETING_NOT_FOUND. */
    @Transactional(readOnly = true)
    public FanMeetingEvent get(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
    }
}
