package com.restless.fanmeeting.domain;

import java.time.Instant;

/** 행사 생성 값. promotionClosesAt만 null 가능(없으면 meetingAt - 24시간). */
public record FanMeetingSettings(
        String title,
        Instant entryOpensAt,
        Instant firstNoticeScheduledAt,
        int winnerLimit,
        int reserveLimit,
        long feeAmount,
        Instant meetingAt,
        Instant promotionClosesAt,
        boolean opensAtPublic) {
}
