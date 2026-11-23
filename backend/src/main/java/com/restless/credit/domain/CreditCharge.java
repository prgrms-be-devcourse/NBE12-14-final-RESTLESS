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

/** PG 결제와 크레딧 지급을 잇는 충전 주문. */
@Entity
@Table(name = "credit_charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditCharge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "credit_charge_id")
    private Long id;

    private Long userId;
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private CreditChargeStatus status;

    private String requestKey;
    private Long paidPaymentId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant paidAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    public static CreditCharge ready(Long userId, long amount, String requestKey, Instant now, Instant expiresAt) {
        CreditCharge charge = new CreditCharge();
        charge.userId = Objects.requireNonNull(userId);
        charge.amount = amount;
        charge.requestKey = Objects.requireNonNull(requestKey);
        charge.status = CreditChargeStatus.READY;
        charge.expiresAt = Objects.requireNonNull(expiresAt);
        charge.createdAt = Objects.requireNonNull(now);
        charge.updatedAt = now;
        return charge;
    }

    public void pay(Long paymentId, Instant now) {
        if (status != CreditChargeStatus.READY) throw new IllegalStateException("Credit charge is not READY");
        paidPaymentId = Objects.requireNonNull(paymentId);
        paidAt = Objects.requireNonNull(now);
        updatedAt = now;
        status = CreditChargeStatus.PAID;
    }
}
