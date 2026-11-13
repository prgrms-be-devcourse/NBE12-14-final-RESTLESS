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

/** 추첨으로 뽑힌 당첨·예비 1명 (fan_meeting_selections). 응모 하나는 최대 한 번 뽑힌다. */
@Entity
@Table(name = "fan_meeting_selections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "selection_id")
    private Long id;

    private Long eventId;

    private Long drawRunId;

    private Long applicationId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingSelectionType selectionType;

    /** 당첨·예비 각각 1부터. */
    private int selectionRank;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingSelectionStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    /** 당첨: 결제 대기. */
    public static FanMeetingSelection winner(FanMeetingDrawRun run, Long applicationId, Long userId, int rank,
                                             Instant now) {
        return of(run, applicationId, userId, FanMeetingSelectionType.WINNER, rank,
                FanMeetingSelectionStatus.PAYMENT_PENDING, now);
    }

    /** 예비: 승격을 기다림. */
    public static FanMeetingSelection reserve(FanMeetingDrawRun run, Long applicationId, Long userId, int rank,
                                              Instant now) {
        return of(run, applicationId, userId, FanMeetingSelectionType.RESERVE, rank,
                FanMeetingSelectionStatus.WAITING, now);
    }

    private static FanMeetingSelection of(FanMeetingDrawRun run, Long applicationId, Long userId,
                                          FanMeetingSelectionType type, int rank, FanMeetingSelectionStatus status,
                                          Instant now) {
        FanMeetingSelection selection = new FanMeetingSelection();
        selection.eventId = run.getEventId();
        selection.drawRunId = run.getId();
        selection.applicationId = applicationId;
        selection.userId = userId;
        selection.selectionType = type;
        selection.selectionRank = rank;
        selection.status = status;
        selection.createdAt = now;
        selection.updatedAt = now;
        return selection;
    }
}
