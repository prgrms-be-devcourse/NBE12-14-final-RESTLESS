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

    public static Drop draft(Long concertId, String title, String description, String imageUrl,
                             Instant opensAt, Instant closesAt, Instant now) {
        if (opensAt == null || closesAt == null || !opensAt.isBefore(closesAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Drop drop = new Drop();
        drop.concertId = concertId;
        drop.title = title;
        drop.description = description;
        drop.imageUrl = imageUrl;
        drop.opensAt = opensAt;
        drop.closesAt = closesAt;
        drop.status = DropStatus.DRAFT;
        drop.createdAt = now;
        return drop;
    }

    public void publish() {
        if (status != DropStatus.DRAFT) throw new BusinessException(ErrorCode.DROP_INVALID_STATE);
        status = DropStatus.SCHEDULED;
    }

    public String saleStatus(Instant now) {
        if (now.isBefore(opensAt)) return "UPCOMING";
        return now.isBefore(closesAt) ? "ON_SALE" : "ENDED";
    }
}
