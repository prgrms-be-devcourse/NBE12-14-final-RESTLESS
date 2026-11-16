package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.domain.FanMeetingSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 행사 생성 요청. promotionClosesAt만 선택(없으면 meetingAt - 24시간).
 * 값 사이 규칙(시각 순서, 인원 하한)은 도메인에서 검증한다.
 */
public record FanMeetingCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull Instant entryOpensAt,
        @NotNull Instant firstNoticeScheduledAt,
        @NotNull Integer winnerLimit,
        @NotNull Integer reserveLimit,
        @NotNull Long feeAmount,
        @NotNull Instant meetingAt,
        Instant promotionClosesAt,
        @NotNull Boolean opensAtPublic) {

    public FanMeetingSettings toSettings() {
        return new FanMeetingSettings(title, entryOpensAt, firstNoticeScheduledAt, winnerLimit, reserveLimit,
                feeAmount, meetingAt, promotionClosesAt, opensAtPublic);
    }
}
