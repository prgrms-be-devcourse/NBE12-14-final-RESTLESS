package com.restless.fanmeeting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 회원의 응모 1건 (fan_meeting_applications). 회원·행사당 1행, 취소 후 재응모는 같은 행. */
@Entity
@Table(name = "fan_meeting_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "application_id")
    private Long id;

    private Long eventId;

    /** users 논리 참조(물리 FK 없음). */
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingApplicationStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant appliedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    private FanMeetingApplication(Long eventId, Long userId, Instant now) {
        this.eventId = eventId;
        this.userId = userId;
        this.status = FanMeetingApplicationStatus.APPLIED;
        this.appliedAt = now;
        this.updatedAt = now;
    }

    /** 첫 응모. */
    public static FanMeetingApplication apply(Long eventId, Long userId, Instant now) {
        return new FanMeetingApplication(eventId, userId, now);
    }

    public boolean isApplied() {
        return status == FanMeetingApplicationStatus.APPLIED;
    }

    /** 취소. applied_at은 유지한다(state-machines.md 2절). */
    public void withdraw(Instant now) {
        this.status = FanMeetingApplicationStatus.WITHDRAWN;
        this.updatedAt = now;
    }

    /** 취소한 응모를 같은 행으로 다시 응모. applied_at을 현재 시각으로 갱신한다. */
    public void reapply(Instant now) {
        this.status = FanMeetingApplicationStatus.APPLIED;
        this.appliedAt = now;
        this.updatedAt = now;
    }
}
