package com.restless.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자·회차별 구매 수량 관리. 신규 행은 선점·확정 수량을 0으로 시작한다. */
@Entity
@Table(name = "ticket_purchase_limits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketPurchaseLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "reserved_count", nullable = false)
    private int reservedCount;

    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount;

    private TicketPurchaseLimit(Long userId, Long performanceId) {
        this.userId = Objects.requireNonNull(userId);
        this.performanceId = Objects.requireNonNull(performanceId);
    }

    public static TicketPurchaseLimit create(Long userId, Long performanceId) {
        return new TicketPurchaseLimit(userId, performanceId);
    }
}
