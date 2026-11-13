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

/**
 * 행사의 추첨 실행 1건 (fan_meeting_draw_runs, 행사당 최대 1건).
 * 마감 트랜잭션에서 PENDING으로 만든다. Claim·lease·최종화 필드는 추첨 구현 때 매핑한다.
 */
@Entity
@Table(name = "fan_meeting_draw_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingDrawRun {

    public static final int SEED_BYTES = 32;
    public static final String ALGORITHM_VERSION = "HMAC_SHA256_RANK_V1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "draw_run_id")
    private Long id;

    private Long eventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private FanMeetingDrawRunStatus status;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "random_seed", length = SEED_BYTES)
    private byte[] randomSeed;

    private String algorithmVersion;

    private int attemptCount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    /** 마감 때 만드는 추첨 실행. seed는 SecureRandom 32바이트(transactions.md 5절). */
    public static FanMeetingDrawRun pending(Long eventId, byte[] randomSeed, Instant now) {
        if (randomSeed.length != SEED_BYTES) {
            throw new IllegalArgumentException("seed는 32바이트여야 합니다: " + randomSeed.length);
        }
        FanMeetingDrawRun run = new FanMeetingDrawRun();
        run.eventId = eventId;
        run.status = FanMeetingDrawRunStatus.PENDING;
        run.randomSeed = randomSeed.clone();
        run.algorithmVersion = ALGORITHM_VERSION;
        run.attemptCount = 0;
        run.createdAt = now;
        run.updatedAt = now;
        return run;
    }
}
