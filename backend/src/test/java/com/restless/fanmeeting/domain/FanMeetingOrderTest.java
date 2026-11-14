package com.restless.fanmeeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주문의 기한 경계 (policy.md 4절, transactions.md 8·9절).
 * 기한 = 발급 + 60분. 포기·확정·승인 시작은 기한 "전"까지만, 만료는 기한과 같은 시각부터다. 1초 전, 같은 시각, 1초 뒤를 본다.
 */
class FanMeetingOrderTest {

    static final Instant ISSUED = Instant.parse("2026-11-20T03:00:00Z");
    static final Instant DEADLINE = ISSUED.plus(Duration.ofMinutes(60));
    static final Instant BEFORE = DEADLINE.minusSeconds(1);
    static final Instant AFTER = DEADLINE.plusSeconds(1);

    @Test
    @DisplayName("결제 기한은 발급 + 60분")
    void 결제_기한은_발급_60분_뒤() {
        assertThat(order(50_000L).paymentDeadline()).isEqualTo(Instant.parse("2026-11-20T04:00:00Z"));
    }

    @Test
    @DisplayName("포기: 기한 1초 전까지 되고, 기한과 같은 시각부터 안 된다")
    void 포기는_기한_전까지만() {
        FanMeetingOrder order = order(50_000L);

        assertThat(order.isDeclinable(BEFORE)).isTrue();
        assertThat(order.isDeclinable(DEADLINE)).isFalse();
        assertThat(order.isDeclinable(AFTER)).isFalse();
    }

    @Test
    @DisplayName("포기: 결제 준비를 요청한 주문, 결제 대기가 아닌 주문은 기한 전이어도 안 된다")
    void 결제를_준비했거나_결제_대기가_아니면_포기할_수_없다() {
        FanMeetingOrder requested = order(50_000L);
        requested.recordAttemptRequested(ISSUED.plusSeconds(10));
        FanMeetingOrder declined = order(50_000L);
        declined.decline(BEFORE);

        assertThat(requested.hasRequestedAttempt()).isTrue();
        assertThat(requested.isDeclinable(BEFORE)).isFalse();
        assertThat(declined.getStatus()).isEqualTo(FanMeetingOrderStatus.CANCELLED);
        assertThat(declined.isDeclinable(BEFORE)).isFalse();
    }

    @Test
    @DisplayName("만료: 기한 1초 전에는 안 하고, 기한과 같은 시각부터 한다")
    void 만료는_기한과_같은_시각부터() {
        FanMeetingOrder order = order(50_000L);

        assertThat(order.isExpirable(BEFORE)).isFalse();
        assertThat(order.isExpirable(DEADLINE)).isTrue();
        assertThat(order.isExpirable(AFTER)).isTrue();
    }

    @Test
    @DisplayName("만료 후보 조회 기준(발급 시각)과 주문의 만료 판정이 같은 경계를 쓴다")
    void 조회_기준과_만료_판정의_경계가_같다() {
        FanMeetingOrder order = order(50_000L);

        assertThat(FanMeetingOrder.issuedAtPastDeadline(BEFORE)).as("기한 1초 전: 발급 시각이 기준보다 뒤라 후보가 아니다")
                .isBefore(order.getIssuedAt());
        assertThat(FanMeetingOrder.issuedAtPastDeadline(DEADLINE)).as("기한과 같은 시각: 후보다(<=)")
                .isEqualTo(order.getIssuedAt());
    }

    @Test
    @DisplayName("만료: 승인이 진행 중인 주문(현재 시도 있음)은 기한이 지나도 대상이 아니다")
    void 승인이_진행_중이면_만료_대상이_아니다() {
        FanMeetingOrder approving = order(50_000L);
        assertThat(approving.startApproval(77L, BEFORE)).isTrue();

        assertThat(approving.isExpirable(AFTER)).isFalse();

        approving.failAttempt(BEFORE);
        assertThat(approving.isExpirable(AFTER)).as("시도가 실패로 끝나 비워지면 다시 대상").isTrue();
    }

    @Test
    @DisplayName("만료: 보류·결제 완료·포기 주문은 대상이 아니다")
    void 결제_대기가_아니면_만료_대상이_아니다() {
        FanMeetingOrder review = order(50_000L);
        review.startApproval(77L, BEFORE);
        review.markReview(BEFORE);
        FanMeetingOrder paid = order(0L);
        paid.confirmFree(BEFORE);
        FanMeetingOrder declined = order(50_000L);
        declined.decline(BEFORE);

        assertThat(review.isExpirable(AFTER)).isFalse();
        assertThat(paid.isExpirable(AFTER)).isFalse();
        assertThat(declined.isExpirable(AFTER)).isFalse();
    }

    @Test
    @DisplayName("0원 확정: PAID, 결제 시각은 확정 시각, 결제 시도 기록은 없다")
    void 무료_확정은_결제_시도_없이_PAID() {
        FanMeetingOrder order = order(0L);

        order.confirmFree(BEFORE);

        assertThat(order.isFree()).isTrue();
        assertThat(order.isPaid()).isTrue();
        assertThat(order.getPaidAt()).isEqualTo(BEFORE);
        assertThat(order.getPaymentId()).isNull();
        assertThat(order.getApprovedAttemptId()).isNull();
        assertThat(order.hasRequestedAttempt()).isFalse();
        assertThat(order(50_000L).isFree()).isFalse();
    }

    @Test
    @DisplayName("보류 경고: 30분 1초 전에는 안 하고, 30분과 같은 시각부터 한다. 한 번 경고하면 다시 안 한다")
    void 보류_경고는_30분부터_한_번() {
        Instant reviewStarted = ISSUED.plus(Duration.ofMinutes(10));
        Instant thirty = reviewStarted.plus(Duration.ofMinutes(30));
        FanMeetingOrder order = order(50_000L);
        order.startApproval(77L, reviewStarted);
        order.markReview(reviewStarted);

        assertThat(order.isReviewStale(thirty.minusSeconds(1))).isFalse();
        assertThat(order.isReviewStale(thirty)).isTrue();
        assertThat(order.isReviewStale(thirty.plusSeconds(1))).isTrue();
        assertThat(FanMeetingOrder.reviewStartedAtToWarn(thirty)).as("조회 기준도 같은 경계(<=)").isEqualTo(reviewStarted);

        order.markReviewWarned(thirty);
        assertThat(order.isReviewStale(thirty.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("보류 경고: 보류에서 벗어났다가 다시 보류가 되면 새 보류 기준으로 다시 경고한다")
    void 새_보류는_다시_경고한다() {
        Instant first = ISSUED.plus(Duration.ofMinutes(1));
        FanMeetingOrder order = order(50_000L);
        order.startApproval(77L, first);
        order.markReview(first);
        order.markReviewWarned(first.plus(Duration.ofMinutes(31)));

        order.failAttempt(first.plus(Duration.ofMinutes(32)));
        Instant second = first.plus(Duration.ofMinutes(33));
        order.startApproval(78L, second);
        order.markReview(second);

        assertThat(order.getReviewWarnedAt()).isNull();
        assertThat(order.isReviewStale(second.plus(Duration.ofMinutes(29)))).isFalse();
        assertThat(order.isReviewStale(second.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문은 보류 경고 대상이 아니다")
    void 보류가_아니면_경고하지_않는다() {
        assertThat(order(50_000L).isReviewStale(AFTER.plus(Duration.ofDays(1)))).isFalse();
    }

    private static FanMeetingOrder order(long amount) {
        FanMeetingDrawRun run = FanMeetingDrawRun.pending(1L, new byte[FanMeetingDrawRun.SEED_BYTES], ISSUED);
        return FanMeetingOrder.issue(FanMeetingSelection.winner(run, 10L, 20L, 1, ISSUED), amount, ISSUED);
    }
}
