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
 * 행사의 추첨 실행 1건 (fan_meeting_draw_runs, 행사당 최대 1건).
 * 마감 트랜잭션에서 PENDING으로 만들고, Draw Worker가 lease(2분)를 잡아 계산한 뒤 최종화한다.
 */
@Entity
@Table(name = "fan_meeting_draw_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FanMeetingDrawRun {

    public static final int SEED_BYTES = 32;
    public static final String ALGORITHM_VERSION = "HMAC_SHA256_RANK_V1";
    static final Duration LEASE = Duration.ofMinutes(2);

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

    /** 탈퇴 회원을 뺀 뒤의 후보 수. 최종화 때 채운다. */
    private Integer candidateCount;

    /** 지금 계산 중인 Worker의 token. 응답·로그에 내보내지 않는다. */
    private String leaseToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant leaseUntil;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant completedAt;

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

    /** PENDING이거나, RUNNING인데 lease가 끝났으면 다른 Worker가 가져갈 수 있다. */
    public boolean isClaimable(Instant now) {
        return status == FanMeetingDrawRunStatus.PENDING
                || (status == FanMeetingDrawRunStatus.RUNNING && leaseUntil.isBefore(now));
    }

    /** 새 token으로 lease를 잡는다. 처음 잡을 때만 시작 시각을 남긴다. */
    public void claim(String leaseToken, Instant now) {
        this.leaseToken = leaseToken;
        this.leaseUntil = now.plus(LEASE);
        this.attemptCount++;
        this.status = FanMeetingDrawRunStatus.RUNNING;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.updatedAt = now;
    }

    public boolean isCompleted() {
        return status == FanMeetingDrawRunStatus.COMPLETED;
    }

    /** 지금 lease를 쥔 Worker의 token인지. lease가 넘어갔으면 늦은 Worker의 결과는 저장하지 않는다. */
    public boolean isHeldBy(String token) {
        return status == FanMeetingDrawRunStatus.RUNNING && leaseToken.equals(token);
    }

    public void complete(int candidateCount, Instant now) {
        this.status = FanMeetingDrawRunStatus.COMPLETED;
        this.candidateCount = candidateCount;
        this.completedAt = now;
        this.updatedAt = now;
    }
}
