package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import java.time.Instant;

/**
 * 행사 상세 응답. 필드는 항상 있고, 공개하지 않는 시각은 null이다.
 * entryOpensAt: 예고 오픈이거나 OPEN 이후면 공개, 게릴라 오픈의 OPEN 전이면 null.
 * entryClosesAt: OPEN 중에만 공개.
 */
public record FanMeetingDetailResponse(
        Long eventId,
        String title,
        FanMeetingStatus status,
        Instant entryOpensAt,
        Instant entryClosesAt,
        Instant meetingAt,
        int winnerLimit,
        int reserveLimit,
        long feeAmount) {

    public static FanMeetingDetailResponse from(FanMeetingEvent event) {
        boolean beforeOpen = event.getStatus() == FanMeetingStatus.SCHEDULED;
        return new FanMeetingDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getStatus(),
                event.isOpensAtPublic() || !beforeOpen ? event.getEntryOpensAt() : null,
                event.getStatus() == FanMeetingStatus.OPEN ? event.getEntryClosesAt() : null,
                event.getMeetingAt(),
                event.getWinnerLimit(),
                event.getReserveLimit(),
                event.getFeeAmount());
    }
}
