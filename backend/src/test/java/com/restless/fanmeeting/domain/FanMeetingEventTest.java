package com.restless.fanmeeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 행사 생성 규칙. 기대값은 명세(docs/domains/fanmeeting/policy.md 3절)에서 가져온다. */
class FanMeetingEventTest {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    /** 오픈 NOW+1시간, 마감 오픈+10분, 팬미팅 마감+48시간(허용 경계)을 기준으로 값을 바꿔 본다. */
    static final Instant OPENS = NOW.plus(Duration.ofHours(1));
    static final Instant CLOSES = OPENS.plus(Duration.ofMinutes(10));
    static final Instant MEETING = CLOSES.plus(Duration.ofHours(48));

    @Test
    void 정상_값이면_SCHEDULED로_만들어지고_응모_마감은_오픈_10분_뒤() {
        FanMeetingEvent event = FanMeetingEvent.create(settings(OPENS, 300, 100, 50_000L, MEETING, null), 7L, NOW);

        assertThat(event.getStatus()).isEqualTo(FanMeetingStatus.SCHEDULED);
        assertThat(event.getEntryClosesAt()).isEqualTo(OPENS.plus(Duration.ofMinutes(10)));
        assertThat(event.getPromotionClosesAt()).isEqualTo(MEETING.minus(Duration.ofHours(24)));
        assertThat(event.getOrganizerId()).isEqualTo(7L);
    }

    @Test
    void 오픈_시각이_현재와_같거나_과거면_거절() {
        Instant meetingAt = NOW.plus(Duration.ofDays(3));

        assertInvalid(new FanMeetingSettings("팬미팅", NOW, NOW, 300, 100, 50_000L, meetingAt, null, true));
        assertInvalid(new FanMeetingSettings("팬미팅", NOW.minusSeconds(1), NOW.minusSeconds(1), 300, 100, 50_000L,
                meetingAt, null, true));
    }

    @Test
    void 당첨_인원은_1명_이상_예비_인원과_참가비는_0_이상() {
        assertInvalid(settings(OPENS, 0, 100, 50_000L, MEETING, null));
        assertInvalid(settings(OPENS, 300, -1, 50_000L, MEETING, null));
        assertInvalid(settings(OPENS, 300, 100, -1L, MEETING, null));

        assertValid(settings(OPENS, 1, 0, 0L, MEETING, null));
    }

    @Test
    void 공지_시각은_오픈_시각과_같거나_앞이어야_한다() {
        assertInvalid(settings(OPENS.plusSeconds(1), 300, 100, 50_000L, MEETING, null));

        assertValid(settings(OPENS, 300, 100, 50_000L, MEETING, null));
    }

    @Test
    void 팬미팅은_응모_마감_48시간_뒤부터() {
        assertInvalid(settings(OPENS, 300, 100, 50_000L, MEETING.minusSeconds(1), null));

        assertValid(settings(OPENS, 300, 100, 50_000L, MEETING, null));
    }

    @Test
    void 승격_마감은_응모_마감_2시간_뒤부터() {
        Instant earliest = CLOSES.plus(Duration.ofHours(2));

        assertInvalid(settings(OPENS, 300, 100, 50_000L, MEETING, earliest.minusSeconds(1)));

        assertValid(settings(OPENS, 300, 100, 50_000L, MEETING, earliest));
    }

    @Test
    void 승격_마감은_팬미팅_3시간_전까지() {
        Instant latest = MEETING.minus(Duration.ofHours(3));

        assertInvalid(settings(OPENS, 300, 100, 50_000L, MEETING, latest.plusSeconds(1)));

        assertValid(settings(OPENS, 300, 100, 50_000L, MEETING, latest));
    }

    private static FanMeetingSettings settings(Instant noticeAt, int winnerLimit, int reserveLimit, long feeAmount,
                                               Instant meetingAt, Instant promotionClosesAt) {
        return new FanMeetingSettings("팬미팅", OPENS, noticeAt, winnerLimit, reserveLimit, feeAmount, meetingAt,
                promotionClosesAt, true);
    }

    private static void assertValid(FanMeetingSettings settings) {
        assertThat(FanMeetingEvent.create(settings, 7L, NOW).getStatus()).isEqualTo(FanMeetingStatus.SCHEDULED);
    }

    private static void assertInvalid(FanMeetingSettings settings) {
        assertThatThrownBy(() -> FanMeetingEvent.create(settings, 7L, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_FAN_MEETING_SETTINGS));
    }
}
