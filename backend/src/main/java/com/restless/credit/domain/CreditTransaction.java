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

/**
 * 크레딧 변동 내역 한 건. 바꾸지 않는다.
 * 같은 작업은 {@code operation_key} UNIQUE로 한 번만 기록된다 (INV-AU-12).
 */
@Entity
@Table(name = "credit_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credit_transaction_id")
    private Long id;

    private Long creditWalletId;

    private Long creditHoldId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CreditTransactionType transactionType;

    private long balanceDelta;

    private long reservedDelta;

    private long balanceAfter;

    private long reservedAfter;

    private String operationKey;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    /** 지갑을 바꾼 직후에 부른다. 변경 후 금액을 지갑에서 읽는다. */
    public static CreditTransaction record(CreditWallet wallet, Long creditHoldId, CreditTransactionType type,
                                           long balanceDelta, long reservedDelta, String operationKey, Instant now) {
        CreditTransaction tx = new CreditTransaction();
        tx.creditWalletId = Objects.requireNonNull(wallet.getId());
        tx.creditHoldId = creditHoldId;
        tx.transactionType = Objects.requireNonNull(type);
        tx.balanceDelta = balanceDelta;
        tx.reservedDelta = reservedDelta;
        tx.balanceAfter = wallet.getBalanceAmount();
        tx.reservedAfter = wallet.getReservedAmount();
        tx.operationKey = Objects.requireNonNull(operationKey);
        tx.createdAt = Objects.requireNonNull(now);
        return tx;
    }
}
