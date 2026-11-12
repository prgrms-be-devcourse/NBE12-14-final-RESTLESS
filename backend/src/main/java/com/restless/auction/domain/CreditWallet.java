package com.restless.auction.domain;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 회원당 하나인 크레딧 지갑. 사용 가능 크레딧 = 보유 − 묶인 금액.
 *
 * <p>금액을 바꾸는 메서드는 지갑 행을 {@code FOR UPDATE}로 잠근 트랜잭션 안에서만 부른다.
 * DB CHECK가 {@code reserved <= balance}를 마지막으로 지킨다 (INV-AU-06).
 */
@Entity
@Table(name = "credit_wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credit_wallet_id")
    private Long id;

    private Long userId;

    private long balanceAmount;

    private long reservedAmount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    public long availableAmount() {
        return balanceAmount - reservedAmount;
    }

    /** 보유액을 늘린다. */
    public void grant(long amount, Instant now) {
        requirePositive(amount);
        balanceAmount = Math.addExact(balanceAmount, amount);
        touch(now);
    }

    /** 입찰 금액을 묶는다. 사용 가능 크레딧이 부족하면 CREDIT_INSUFFICIENT. */
    public void reserve(long amount, Instant now) {
        requirePositive(amount);
        if (availableAmount() < amount) {
            throw new BusinessException(ErrorCode.CREDIT_INSUFFICIENT);
        }
        reservedAmount += amount;
        touch(now);
    }

    /** 묶인 금액을 푼다 (추월). */
    public void release(long amount, Instant now) {
        requirePositive(amount);
        if (reservedAmount < amount) {
            throw new IllegalStateException("Release exceeds reserved amount");
        }
        reservedAmount -= amount;
        touch(now);
    }

    /** 묶인 금액을 보유액에서 차감한다 (낙찰). */
    public void capture(long amount, Instant now) {
        requirePositive(amount);
        if (reservedAmount < amount || balanceAmount < amount) {
            throw new IllegalStateException("Capture exceeds reserved or balance amount");
        }
        reservedAmount -= amount;
        balanceAmount -= amount;
        touch(now);
    }

    private void touch(Instant now) {
        updatedAt = Objects.requireNonNull(now);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
