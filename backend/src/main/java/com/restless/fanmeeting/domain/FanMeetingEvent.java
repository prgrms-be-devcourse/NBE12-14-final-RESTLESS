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
import java.time.LocalTime;
import java.time.ZoneId;
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

    /** 야간 승격 중지 (policy.md 7절): Asia/Seoul 23:00부터 09:00 전까지. */
    private static final ZoneId PROMOTION_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_STARTS = LocalTime.of(23, 0);
    private static final LocalTime NIGHT_ENDS = LocalTime.of(9, 0);

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
        Schedule schedule = validate(settings, now);
        return new FanMeetingEvent(settings, organizerId, schedule.entryClosesAt(), schedule.promotionClosesAt(), now);
    }

    private record Schedule(Instant entryClosesAt, Instant promotionClosesAt) {
    }

    private static Schedule validate(FanMeetingSettings settings, Instant now) {
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
        return new Schedule(entryClosesAt, promotionClosesAt);
    }

    public void requireEditable(Instant now) {
        if (status != FanMeetingStatus.SCHEDULED || !now.isBefore(entryOpensAt)) {
            throw new BusinessException(ErrorCode.FAN_MEETING_NOT_EDITABLE);
        }
    }

    public boolean cancel(Instant now) {
        if (status == FanMeetingStatus.CANCELLED) {
            return false;
        }
        requireEditable(now);
        status = FanMeetingStatus.CANCELLED;
        updatedAt = now;
        return true;
    }

    public void update(FanMeetingSettings settings, Instant now) {
        requireEditable(now);
        Schedule schedule = validate(settings, now);
        title = settings.title();
        entryOpensAt = settings.entryOpensAt();
        entryClosesAt = schedule.entryClosesAt();
        firstNoticeScheduledAt = settings.firstNoticeScheduledAt();
        winnerLimit = settings.winnerLimit();
        reserveLimit = settings.reserveLimit();
        feeAmount = settings.feeAmount();
        meetingAt = settings.meetingAt();
        promotionClosesAt = schedule.promotionClosesAt();
        opensAtPublic = settings.opensAtPublic();
        updatedAt = now;
    }

    /** 응모·취소를 받을 수 있는지. OPEN이고 entry_opens_at <= now < entry_closes_at. 아니면 APPLICATION_WINDOW_CLOSED. */
    public void requireApplicationWindow(Instant now) {
        if (status != FanMeetingStatus.OPEN || now.isBefore(entryOpensAt) || !now.isBefore(entryClosesAt)) {
            throw new BusinessException(ErrorCode.APPLICATION_WINDOW_CLOSED);
        }
    }

    /** Open Worker: SCHEDULED이고 entry_opens_at <= now < entry_closes_at이면 열 수 있다. */
    public boolean canOpen(Instant now) {
        return status == FanMeetingStatus.SCHEDULED && !now.isBefore(entryOpensAt) && now.isBefore(entryClosesAt);
    }

    /** 서버가 멈췄다 돌아와 마감 시각까지 지났으면 열지 않는다(FANMEETING_OPEN_MISSED). */
    public boolean isOpenMissed(Instant now) {
        return status == FanMeetingStatus.SCHEDULED && !now.isBefore(entryClosesAt);
    }

    public void open(Instant now) {
        this.status = FanMeetingStatus.OPEN;
        this.updatedAt = now;
    }

    /** Close Worker: OPEN이고 entry_closes_at <= now이면 닫을 수 있다. */
    public boolean canClose(Instant now) {
        return status == FanMeetingStatus.OPEN && !now.isBefore(entryClosesAt);
    }

    public void close(Instant now) {
        this.status = FanMeetingStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    /** 추첨 Claim: CLOSED면 DRAWING. 이미 DRAWING이면(lease 만료 뒤 재Claim) 그대로. */
    public void startDrawing(Instant now) {
        if (status == FanMeetingStatus.CLOSED) {
            this.status = FanMeetingStatus.DRAWING;
            this.updatedAt = now;
        }
    }

    /** 추첨 결과가 확정됐는지. 최종화가 행사를 결제 진행 중(후보 0명이면 모집 완료)으로 바꾼다. */
    public boolean isDrawFinalized() {
        return status == FanMeetingStatus.PAYMENT_IN_PROGRESS || status == FanMeetingStatus.RECRUITMENT_COMPLETED;
    }

    public boolean isPaymentInProgress() {
        return status == FanMeetingStatus.PAYMENT_IN_PROGRESS;
    }

    /** 정원 점유 현황. 결제 대기에는 보류 중인 자리도 들어 있다(보류 중에도 선발은 결제 대기다). */
    public record Occupancy(long confirmed, long paymentPending, long waiting, long review) {
    }

    /**
     * 지금 예비를 승격해도 되는지. 야간(Asia/Seoul 23:00부터 09:00 전까지)이 아니고,
     * 새 주문의 기한(now + 60분)이 승격 마감을 넘지 않을 때만 된다. 기한이 마감과 같으면 된다.
     */
    public boolean isPromotionAllowed(Instant now) {
        LocalTime time = now.atZone(PROMOTION_ZONE).toLocalTime();
        boolean night = !time.isBefore(NIGHT_STARTS) || time.isBefore(NIGHT_ENDS);
        return !night && !now.plus(FanMeetingOrder.PAYMENT_WINDOW).isAfter(promotionClosesAt);
    }

    /** 최초 당첨 주문의 결제 기한이 승격 마감을 넘지 않을 때만 추첨을 진행한다. */
    public boolean isDrawAllowed(Instant now) {
        return !now.plus(FanMeetingOrder.PAYMENT_WINDOW).isAfter(promotionClosesAt);
    }

    /** 오픈 전 취소도 게릴라 시각을 공개하지 않는다. */
    public Instant publicEntryOpensAt() {
        boolean beforeOpen = status == FanMeetingStatus.SCHEDULED || status == FanMeetingStatus.CANCELLED;
        return opensAtPublic || !beforeOpen ? entryOpensAt : null;
    }

    public Instant publicEntryClosesAt() {
        return status == FanMeetingStatus.OPEN ? entryClosesAt : null;
    }

    /** 빈자리 = 정원 − (확정 + 결제 대기). */
    public long vacancy(long occupied) {
        return Math.max(0, winnerLimit - occupied);
    }

    /**
     * 모집을 끝낼 때인지. 보류가 0건이고 ① 확정이 정원 이상 ② 예비·결제 대기 모두 0 ③ 승격 마감이 지났고
     * 결제 대기 0 중 하나. 승격 마감과 같은 시각은 지난 것으로 본다.
     */
    public boolean isRecruitmentDone(Occupancy counts, Instant now) {
        if (counts.review() > 0) {
            return false;
        }
        boolean full = counts.confirmed() >= winnerLimit;
        boolean nobodyLeft = counts.waiting() == 0 && counts.paymentPending() == 0;
        boolean promotionClosed = !now.isBefore(promotionClosesAt) && counts.paymentPending() == 0;
        return full || nobodyLeft || promotionClosed;
    }

    public void completeRecruitment(Instant now) {
        this.status = FanMeetingStatus.RECRUITMENT_COMPLETED;
        this.updatedAt = now;
    }

    /** 추첨 최종화: 후보가 있으면 결제 진행, 없으면 바로 모집 완료. */
    public void finishDraw(boolean hasCandidates, Instant now) {
        this.status = hasCandidates ? FanMeetingStatus.PAYMENT_IN_PROGRESS : FanMeetingStatus.RECRUITMENT_COMPLETED;
        this.updatedAt = now;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_FAN_MEETING_SETTINGS, message);
    }
}
