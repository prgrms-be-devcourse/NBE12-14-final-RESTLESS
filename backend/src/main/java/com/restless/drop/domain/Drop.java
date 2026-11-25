package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "drops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Drop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "concert_id", nullable = false)
    private Long concertId;
    @Column(name = "organizer_id")
    private Long organizerId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;
    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DropStatus status;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public static Drop draft(Long concertId, Long organizerId, String title, String description, String imageUrl,
                             Instant opensAt, Instant closesAt, Instant now) {
        if (opensAt == null || closesAt == null || !opensAt.isBefore(closesAt)
                || !closesAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Drop drop = new Drop();
        drop.concertId = concertId;
        drop.organizerId = organizerId;
        drop.title = title;
        drop.description = description;
        drop.imageUrl = imageUrl;
        drop.opensAt = opensAt;
        drop.closesAt = closesAt;
        drop.status = DropStatus.DRAFT;
        drop.createdAt = now;
        return drop;
    }

    /** 주최자는 본인 드롭만 관리한다. 관리자는 모든 드롭을 관리한다. */
    public boolean isManageableBy(Long actorId, boolean admin) {
        return admin || (organizerId != null && organizerId.equals(actorId));
    }

    public void publish() {
        if (status != DropStatus.DRAFT) throw new BusinessException(ErrorCode.DROP_INVALID_STATE);
        status = DropStatus.SCHEDULED;
    }

    /**
     * 삭제 (#430). 판매 중이어도 삭제할 수 있다. 판매 가능 상태가 아니게 되어 새 점유와 결제 시작이 막힌다.
     * 결제 완료 건의 환불은 00:30 배치가 한다.
     */
    public void cancel(Instant now) {
        if (status == DropStatus.CANCELLED) throw new BusinessException(ErrorCode.DROP_INVALID_STATE);
        status = DropStatus.CANCELLED;
        cancelledAt = now;
    }

    public boolean isCancelled() {
        return status == DropStatus.CANCELLED;
    }

    public String saleStatus(Instant now) {
        if (!hasSaleEnabledStatus()) return "ENDED";
        if (now.isBefore(opensAt)) return "UPCOMING";
        return now.isBefore(closesAt) ? "ON_SALE" : "ENDED";
    }

    public boolean isOnSale(Instant now) {
        return "ON_SALE".equals(saleStatus(now));
    }

    private boolean hasSaleEnabledStatus() {
        return status == DropStatus.SCHEDULED || status == DropStatus.OPEN;
    }
}
