package com.restless.fanmeeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 승격을 해도 되는 시각과 모집 완료 판정 (`transactions.md` 10절, `policy.md` 4절).
 * 야간은 Asia/Seoul 23:00부터 09:00 전까지다. 23:00:00은 야간, 09:00:00은 야간이 아니다.
 */
@DisplayName("팬미팅 승격 시각·모집 완료 규칙")
class FanMeetingPromotionRuleTest {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    static final Instant OPENS = NOW.plus(Duration.ofHours(1));
    static final Instant MEETING = OPENS.plus(Duration.ofDays(5));
    /** 승격 마감: 2026-11-16 15:00 KST. 마감 60분 전(14:00)도 낮이라 야간 규칙과 섞이지 않는다. */
    static final Instant PROMOTION_CLOSES = Instant.parse("2026-11-16T06:00:00Z");

    private final FanMeetingEvent event = FanMeetingEvent.create(
            new FanMeetingSettings("팬미팅", OPENS, OPENS, 3, 2, 50_000L, MEETING, PROMOTION_CLOSES, true), 7L, NOW);

    @Test
    @DisplayName("야간 경계: 22:59:59 허용, 23:00:00 금지, 08:59:59 금지, 09:00:00 허용 (Asia/Seoul)")
    void 야간에는_승격하지_않는다() {
        assertThat(event.isPromotionAllowed(kst("2026-11-13T22:59:59"))).isTrue();
        assertThat(event.isPromotionAllowed(kst("2026-11-13T23:00:00"))).isFalse();
        assertThat(event.isPromotionAllowed(kst("2026-11-14T00:00:00"))).isFalse();
        assertThat(event.isPromotionAllowed(kst("2026-11-14T08:59:59"))).isFalse();
        assertThat(event.isPromotionAllowed(kst("2026-11-14T09:00:00"))).isTrue();
        assertThat(event.isPromotionAllowed(kst("2026-11-14T15:00:00"))).isTrue();
    }

    @Test
    @DisplayName("승격 마감 경계: now + 60분이 마감과 같으면 허용, 1초라도 넘으면 금지")
    void 새_주문의_기한이_승격_마감을_넘으면_승격하지_않는다() {
        Instant lastChance = PROMOTION_CLOSES.minus(Duration.ofMinutes(60));

        assertThat(event.isPromotionAllowed(lastChance.minusSeconds(1))).isTrue();
        assertThat(event.isPromotionAllowed(lastChance)).isTrue();
        assertThat(event.isPromotionAllowed(lastChance.plusSeconds(1))).isFalse();
        assertThat(event.isPromotionAllowed(PROMOTION_CLOSES)).isFalse();
    }

    @Test
    @DisplayName("빈자리 = 정원 − (확정 + 결제 대기). 음수는 0")
    void 빈자리를_센다() {
        assertThat(event.vacancy(0)).isEqualTo(3);
        assertThat(event.vacancy(2)).isEqualTo(1);
        assertThat(event.vacancy(3)).isZero();
        assertThat(event.vacancy(4)).isZero();
    }

    @Test
    @DisplayName("모집 완료 ①: 확정 인원이 정원 이상이면 완료 (예비가 남아 있어도)")
    void 정원이_다_차면_완료한다() {
        assertThat(event.isRecruitmentDone(counts(3, 0, 2, 0), NOW)).isTrue();
        assertThat(event.isRecruitmentDone(counts(2, 1, 2, 0), NOW)).isFalse();
    }

    @Test
    @DisplayName("모집 완료 ②: 예비와 결제 대기가 모두 0이면 완료 (자리가 남아도 채울 사람이 없다)")
    void 채울_사람이_없으면_완료한다() {
        assertThat(event.isRecruitmentDone(counts(1, 0, 0, 0), NOW)).isTrue();
        assertThat(event.isRecruitmentDone(counts(1, 1, 0, 0), NOW)).isFalse();
        assertThat(event.isRecruitmentDone(counts(1, 0, 1, 0), NOW)).isFalse();
    }

    @Test
    @DisplayName("모집 완료 ③: 승격 마감이 지났고 결제 대기 0이면 완료. 마감과 같은 시각은 지난 것, 1초 전은 아님")
    void 승격_마감이_지나고_결제_대기가_없으면_완료한다() {
        assertThat(event.isRecruitmentDone(counts(1, 0, 2, 0), PROMOTION_CLOSES.minusSeconds(1))).isFalse();
        assertThat(event.isRecruitmentDone(counts(1, 0, 2, 0), PROMOTION_CLOSES)).isTrue();
        assertThat(event.isRecruitmentDone(counts(1, 1, 2, 0), PROMOTION_CLOSES.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("보류(PAYMENT_REVIEW)가 1건이라도 있으면 어떤 조건이어도 완료하지 않는다")
    void 보류가_있으면_완료하지_않는다() {
        assertThat(event.isRecruitmentDone(counts(3, 0, 0, 1), PROMOTION_CLOSES.plusSeconds(1))).isFalse();
        assertThat(event.isRecruitmentDone(counts(1, 0, 0, 1), NOW)).isFalse();
    }

    @Test
    @DisplayName("모집 완료하면 행사는 RECRUITMENT_COMPLETED")
    void 모집을_완료한다() {
        event.completeRecruitment(NOW);

        assertThat(event.getStatus()).isEqualTo(FanMeetingStatus.RECRUITMENT_COMPLETED);
        assertThat(event.getUpdatedAt()).isEqualTo(NOW);
    }

    private static FanMeetingEvent.Occupancy counts(long confirmed, long paymentPending, long waiting, long review) {
        return new FanMeetingEvent.Occupancy(confirmed, paymentPending, waiting, review);
    }

    /** Asia/Seoul 벽시계 시각(UTC+9, 서머타임 없음). */
    private static Instant kst(String localDateTime) {
        return Instant.parse(localDateTime + "+09:00");
    }
}
