package com.restless.fanmeeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 결제 기회 1건 (fan_meeting_orders). 당첨자는 추첨 때, 예비자는 승격 때 생긴다.
 * 금액은 만들 때의 참가비를 그대로 저장한다(요청 값을 쓰지 않음).
 *
 * <p>결제 결과 전이는 state-machines.md 4절을 따른다. 결제 메서드는 결제 모듈의 Handler 호출 안에서만 부른다.
 */
@Entity
@Table(name = "fan_meeting_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingOrder {

    /** 결제 기한 = 발급 + 60분 (policy.md 4절). */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(60);

    /** 보류가 이만큼 이어지면 경고한다 (policy.md 7절). */
    static final Duration REVIEW_WARNING_AFTER = Duration.ofMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    private Long eventId;

    private Long selectionId;

    private Long userId;

    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingOrderStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant issuedAt;

    /** 승인이 진행 중이거나 결과를 기다리는 결제 시도({@code payments.id}). 시도가 실패로 끝나면 비운다. */
    private Long paymentId;

    /** 결제 준비를 처음 요청한 시각. 한 번 기록하면 지우지 않는다(결제 포기 판정). */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant attemptRequestedAt;

    /** 승인을 처음 반영한 결제 시도. */
    private String approvedAttemptId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant paidAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant reviewStartedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant reviewWarnedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    /** 선발에 대한 결제 기회. 금액은 행사 참가비. */
    public static FanMeetingOrder issue(FanMeetingSelection selection, long amount, Instant now) {
        FanMeetingOrder order = new FanMeetingOrder();
        order.eventId = selection.getEventId();
        order.selectionId = selection.getId();
        order.userId = selection.getUserId();
        order.amount = amount;
        order.status = FanMeetingOrderStatus.PENDING_PAYMENT;
        order.issuedAt = now;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /** 결제 기한. 이 메서드에서만 계산한다 (policy.md 2절). */
    public Instant paymentDeadline() {
        return issuedAt.plus(PAYMENT_WINDOW);
    }

    /** 기한이 지났는지. 기한과 같은 시각도 지난 것으로 본다. */
    public boolean isPastDeadline(Instant now) {
        return !now.isBefore(paymentDeadline());
    }

    /** 이 시각 이전(같은 시각 포함)에 발급된 주문은 now에 기한이 지났다. 만료 대상을 찾는 조회에 쓴다. */
    public static Instant issuedAtPastDeadline(Instant now) {
        return now.minus(PAYMENT_WINDOW);
    }

    /** 이 시각 이전(같은 시각 포함)에 시작한 보류는 now에 경고 대상이다. */
    public static Instant reviewStartedAtToWarn(Instant now) {
        return now.minus(REVIEW_WARNING_AFTER);
    }

    public static long reviewWarningMinutes() {
        return REVIEW_WARNING_AFTER.toMinutes();
    }

    public boolean isPendingPayment() {
        return status == FanMeetingOrderStatus.PENDING_PAYMENT;
    }

    public boolean isPaid() {
        return status == FanMeetingOrderStatus.PAID;
    }

    /** 참가비 0원. 공통 결제를 거치지 않고 확정한다. */
    public boolean isFree() {
        return amount == 0;
    }

    /** 결제 준비를 한 번이라도 요청했는지. 요청했으면 포기할 수 없다. */
    public boolean hasRequestedAttempt() {
        return attemptRequestedAt != null;
    }

    /** 지금 포기할 수 있는지: 결제 대기, 결제 준비 요청 없음, 기한 전. */
    public boolean isDeclinable(Instant now) {
        return isPendingPayment() && !hasRequestedAttempt() && !isPastDeadline(now);
    }

    /** 결제 준비 전 포기: CANCELLED. 포기할 수 있는지는 부르는 쪽이 먼저 확인한다. */
    public void decline(Instant now) {
        status = FanMeetingOrderStatus.CANCELLED;
        updatedAt = now;
    }

    /** 0원 주문을 결제 없이 확정: PAID. 결제 시도가 없으므로 시도 기록은 비워 둔다. */
    public void confirmFree(Instant now) {
        status = FanMeetingOrderStatus.PAID;
        paidAt = now;
        updatedAt = now;
    }

    /**
     * 만료 Worker가 만료할 주문인지: 결제 대기, 기한 경과, 진행 중인 시도 없음.
     * 승인이 진행 중인 주문은 결제 모듈이 결과를 알려 주므로 건드리지 않는다.
     */
    public boolean isExpirable(Instant now) {
        return isPendingPayment() && paymentId == null && isPastDeadline(now);
    }

    public void expire(Instant now) {
        status = FanMeetingOrderStatus.EXPIRED;
        updatedAt = now;
    }

    /** 보류가 30분을 넘었고 이번 보류에서 아직 경고하지 않았는지. 30분과 같은 시각도 넘은 것으로 본다. */
    public boolean isReviewStale(Instant now) {
        return status == FanMeetingOrderStatus.PAYMENT_REVIEW && reviewStartedAt != null && reviewWarnedAt == null
                && !now.isBefore(reviewStartedAt.plus(REVIEW_WARNING_AFTER));
    }

    /** 보류 경고를 남겼다고 기록한다. 보류에서 나가면 지워져 다음 보류 때 다시 경고한다. */
    public void markReviewWarned(Instant now) {
        reviewWarnedAt = now;
        updatedAt = now;
    }

    /** 결제 준비 요청을 기록한다. 이미 기록돼 있으면 그대로 둔다. */
    public void recordAttemptRequested(Instant now) {
        if (attemptRequestedAt == null) {
            attemptRequestedAt = now;
            updatedAt = now;
        }
    }

    /** 승인 시작. 결제 대기이고 기한 전이면 이 시도를 현재 시도로 기록하고 true. */
    public boolean startApproval(Long attemptId, Instant now) {
        if (!isPendingPayment() || isPastDeadline(now)) {
            return false;
        }
        paymentId = attemptId;
        updatedAt = now;
        return true;
    }

    /** 승인 진행 중인 시도인지. */
    public boolean isCurrentAttempt(Long attemptId) {
        return paymentId != null && paymentId.equals(attemptId);
    }

    /** 결제 대기 또는 보류. 승인·실패 결과를 반영할 수 있는 상태. */
    public boolean isAwaitingResult() {
        return status == FanMeetingOrderStatus.PENDING_PAYMENT || status == FanMeetingOrderStatus.PAYMENT_REVIEW;
    }

    /** 이 시도의 승인을 이미 반영했는지. */
    public boolean isApprovedBy(Long attemptId) {
        return String.valueOf(attemptId).equals(approvedAttemptId);
    }

    /** 기한 안 승인: PAID. */
    public void pay(Long attemptId, Instant approvedAt, Instant now) {
        status = FanMeetingOrderStatus.PAID;
        approvedAttemptId = String.valueOf(attemptId);
        paidAt = approvedAt;
        leaveReview();
        updatedAt = now;
    }

    /** 늦은 승인·금액 불일치: 확정하지 않고 환불 대기. 결제 모듈이 보상 취소한다. */
    public void markRefundPending(Long attemptId, Instant now) {
        status = FanMeetingOrderStatus.REFUND_PENDING;
        approvedAttemptId = String.valueOf(attemptId);
        leaveReview();
        updatedAt = now;
    }

    /** 보상 취소 완료: 환불 대기 → 환불됨. 이 시도로 환불 대기가 된 주문이 아니면 바꾸지 않고 false. */
    public boolean completeRefund(Long attemptId, Instant now) {
        if (status != FanMeetingOrderStatus.REFUND_PENDING || !isApprovedBy(attemptId)) {
            return false;
        }
        status = FanMeetingOrderStatus.REFUNDED;
        updatedAt = now;
        return true;
    }

    /**
     * 이 시도가 최종 실패했다. 기한이 남았으면 다시 결제할 수 있게 결제 대기로 두고 false,
     * 기한이 지났으면 EXPIRED로 바꾸고 true.
     */
    public boolean failAttempt(Instant now) {
        paymentId = null;
        leaveReview();
        updatedAt = now;
        if (isPastDeadline(now)) {
            status = FanMeetingOrderStatus.EXPIRED;
            return true;
        }
        status = FanMeetingOrderStatus.PENDING_PAYMENT;
        return false;
    }

    /** 결과 모름: 결제 대기면 보류로 바꾸고 보류 시작 시각을 기록한다. 이미 보류면 아무것도 바꾸지 않는다. */
    public void markReview(Instant now) {
        if (status == FanMeetingOrderStatus.PENDING_PAYMENT) {
            status = FanMeetingOrderStatus.PAYMENT_REVIEW;
            reviewStartedAt = now;
            updatedAt = now;
        }
    }

    private void leaveReview() {
        reviewStartedAt = null;
        reviewWarnedAt = null;
    }
}
