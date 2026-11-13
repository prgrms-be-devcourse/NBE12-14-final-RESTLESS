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
 */
@Entity
@Table(name = "fan_meeting_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingOrder {

    /** 결제 기한 = 발급 + 60분 (policy.md 4절). */
    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(60);

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
}
