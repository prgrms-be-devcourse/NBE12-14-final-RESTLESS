package com.restless.drop.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "drop_queue_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropQueueEntry {
    // The server-generated identity is also the approximate arrival sequence.
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "drop_id", nullable = false) private Long dropId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private DropQueueStatus status;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC) @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC) @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC) @Column(name = "admitted_at")
    private Instant admittedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC) @Column(name = "admitted_until")
    private Instant admittedUntil;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC) @Column(name = "consumed_at")
    private Instant consumedAt;

    public static DropQueueEntry waiting(Long dropId, Long userId, Instant now) {
        var entry = new DropQueueEntry();
        entry.dropId = dropId;
        entry.userId = userId;
        entry.status = DropQueueStatus.WAITING;
        entry.enteredAt = now;
        entry.lastSeenAt = now;
        return entry;
    }

    public boolean live() { return status == DropQueueStatus.WAITING || status == DropQueueStatus.ADMITTED; }

    public void refresh(Instant now, Duration heartbeatTimeout, boolean onSale) {
        if (status == DropQueueStatus.WAITING
                && (!onSale || lastSeenAt.isBefore(now.minus(heartbeatTimeout)))) {
            status = DropQueueStatus.CLOSED;
        } else if (status == DropQueueStatus.ADMITTED && !now.isBefore(admittedUntil)) {
            status = DropQueueStatus.EXPIRED;
        }
    }

    public void heartbeat(Instant now) { lastSeenAt = now; }

    public void admit(Instant now, Duration ttl) {
        if (status != DropQueueStatus.WAITING) throw new IllegalStateException("Entry is not waiting");
        status = DropQueueStatus.ADMITTED;
        admittedAt = now;
        admittedUntil = now.plus(ttl);
    }

    public boolean validAdmission(Instant now) {
        return status == DropQueueStatus.ADMITTED && now.isBefore(admittedUntil);
    }

    public void consume(Instant now) {
        if (!validAdmission(now)) throw new IllegalStateException("Admission is not valid");
        status = DropQueueStatus.CONSUMED;
        consumedAt = now;
    }
}
