package com.restless.fanmeeting.domain;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
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

/** 팬미팅 행사 (fan_meeting_events). */
@Entity
@Table(name = "fan_meeting_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingEvent {

    static final Duration ENTRY_PERIOD = Duration.ofMinutes(10);
    static final Duration DEFAULT_PROMOTION_CLOSE_BEFORE_MEETING = Duration.ofHours(24);
    static final Duration MIN_MEETING_AFTER_ENTRY_CLOSE = Duration.ofHours(48);
    static final Duration MIN_PROMOTION_CLOSE_AFTER_ENTRY_CLOSE = Duration.ofHours(2);
    static final Duration MIN_PROMOTION_CLOSE_BEFORE_MEETING = Duration.ofHours(3);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    /** 만든 회원 ID. users로 FK 없음(모듈 경계). */
    private Long organizerId;

    private String title;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant entryOpensAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant entryClosesAt;

    /** true면 예고 오픈(OPEN 전에도 오픈 시각 공개), false면 게릴라 오픈. */
    private boolean opensAtPublic;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant firstNoticeScheduledAt;

    private int winnerLimit;

    private int reserveLimit;

    private long feeAmount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant meetingAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant promotionClosesAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant closedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    private FanMeetingEvent(FanMeetingSettings settings, Long organizerId, Instant entryClosesAt,
                            Instant promotionClosesAt, Instant now) {
        this.title = settings.title();
        this.status = FanMeetingStatus.SCHEDULED;
        this.entryOpensAt = settings.entryOpensAt();
        this.entryClosesAt = entryClosesAt;
        this.firstNoticeScheduledAt = settings.firstNoticeScheduledAt();
        this.winnerLimit = settings.winnerLimit();
        this.reserveLimit = settings.reserveLimit();
        this.feeAmount = settings.feeAmount();
        this.meetingAt = settings.meetingAt();
        this.promotionClosesAt = promotionClosesAt;
        this.opensAtPublic = settings.opensAtPublic();
        this.organizerId = organizerId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 생성 규칙을 거친 행사만 만들어지게 이 메서드로만 만든다. now는 시각 검증에 쓴다. */
    public static FanMeetingEvent create(FanMeetingSettings settings, Long organizerId, Instant now) {
        if (!settings.entryOpensAt().isAfter(now)) {
            throw invalid("응모 오픈 시각은 현재보다 뒤여야 합니다.");
        }
        if (settings.winnerLimit() < 1) {
            throw invalid("당첨 인원은 1명 이상이어야 합니다.");
        }
        if (settings.reserveLimit() < 0) {
            throw invalid("예비 인원은 0명 이상이어야 합니다.");
        }
        if (settings.feeAmount() < 0) {
            throw invalid("참가비는 0원 이상이어야 합니다.");
        }
        if (settings.firstNoticeScheduledAt().isAfter(settings.entryOpensAt())) {
            throw invalid("공지 시각은 응모 오픈 시각과 같거나 앞이어야 합니다.");
        }
        Instant entryClosesAt = settings.entryOpensAt().plus(ENTRY_PERIOD);
        if (settings.meetingAt().isBefore(entryClosesAt.plus(MIN_MEETING_AFTER_ENTRY_CLOSE))) {
            throw invalid("팬미팅 시각은 응모 마감 48시간 뒤부터 정할 수 있습니다.");
        }
        Instant promotionClosesAt = settings.promotionClosesAt() != null
                ? settings.promotionClosesAt()
                : settings.meetingAt().minus(DEFAULT_PROMOTION_CLOSE_BEFORE_MEETING);
        if (promotionClosesAt.isBefore(entryClosesAt.plus(MIN_PROMOTION_CLOSE_AFTER_ENTRY_CLOSE))) {
            throw invalid("승격 마감은 응모 마감 2시간 뒤부터 정할 수 있습니다.");
        }
        if (promotionClosesAt.isAfter(settings.meetingAt().minus(MIN_PROMOTION_CLOSE_BEFORE_MEETING))) {
            throw invalid("승격 마감은 팬미팅 3시간 전까지 정할 수 있습니다.");
        }
        return new FanMeetingEvent(settings, organizerId, entryClosesAt, promotionClosesAt, now);
    }

    /** 응모·취소를 받을 수 있는지. OPEN이고 entry_opens_at <= now < entry_closes_at. 아니면 APPLICATION_WINDOW_CLOSED. */
    public void requireApplicationWindow(Instant now) {
        if (status != FanMeetingStatus.OPEN || now.isBefore(entryOpensAt) || !now.isBefore(entryClosesAt)) {
            throw new BusinessException(ErrorCode.APPLICATION_WINDOW_CLOSED);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_FAN_MEETING_SETTINGS, message);
    }
}
