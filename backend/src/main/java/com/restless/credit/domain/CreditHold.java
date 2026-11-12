package com.restless.credit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 입찰 하나에 묶인 크레딧. HELD → RELEASED / CAPTURED. */
@Entity
@Table(name = "credit_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credit_hold_id")
    private Long id;

    private Long creditWalletId;

    private Long auctionBidId;

    private long heldAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CreditHoldStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant heldAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant releasedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant capturedAt;

    public static CreditHold held(Long creditWalletId, Long auctionBidId, long amount, Instant now) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        CreditHold hold = new CreditHold();
        hold.creditWalletId = Objects.requireNonNull(creditWalletId);
        hold.auctionBidId = Objects.requireNonNull(auctionBidId);
        hold.heldAmount = amount;
        hold.status = CreditHoldStatus.HELD;
        hold.heldAt = Objects.requireNonNull(now);
        return hold;
    }

    public void release(Instant now) {
        requireHeld();
        status = CreditHoldStatus.RELEASED;
        releasedAt = Objects.requireNonNull(now);
    }

    public void capture(Instant now) {
        requireHeld();
        status = CreditHoldStatus.CAPTURED;
        capturedAt = Objects.requireNonNull(now);
    }

    private void requireHeld() {
        if (status != CreditHoldStatus.HELD) {
            throw new IllegalStateException("Credit hold is not HELD: " + status);
        }
    }
}
