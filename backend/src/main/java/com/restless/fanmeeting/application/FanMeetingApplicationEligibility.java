package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 가입 시각 검사 (transactions.md 3절). 응모 트랜잭션 전에 한다(잠금을 쥔 채 회원 모듈을 부르지 않음).
 * 설정이 켜져 있을 때만, 행사 공지 시각 이후에 가입한 회원의 응모를 막는다. 기본은 꺼짐.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingApplicationEligibility {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingApplicationEligibility.class);

    private final FanMeetingEventRepository events;
    private final MemberPort memberPort;

    @Value("${restless.fanmeeting.eligibility.joined-at-check.enabled:false}")
    private boolean enabled;

    /** 자격이 없으면 403 APPLICATION_NOT_ELIGIBLE, 가입 시각을 못 읽으면 503 MEMBER_LOOKUP_FAILED. 행사가 없으면 응모가 404로 답한다. */
    public void check(Long userId, Long eventId) {
        if (!enabled) {
            return;
        }
        Instant noticeAt = events.findById(eventId).map(e -> e.getFirstNoticeScheduledAt()).orElse(null);
        if (noticeAt == null) {
            return;
        }
        Instant joinedAt;
        try {
            joinedAt = memberPort.joinedAt(userId);
        } catch (RuntimeException e) {
            log.warn("FANMEETING_MEMBER_LOOKUP_FAILED eventId={}", eventId, e);
            throw new BusinessException(ErrorCode.MEMBER_LOOKUP_FAILED);
        }
        if (!joinedAt.isBefore(noticeAt)) {
            throw new BusinessException(ErrorCode.APPLICATION_NOT_ELIGIBLE);
        }
    }
}
