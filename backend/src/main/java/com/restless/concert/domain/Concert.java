package com.restless.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "concerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "layout_id")
    private Long layoutId;

    @Column(length = 5000)
    private String description;

    @Column(name = "period_start_date")
    private LocalDate periodStartDate;

    @Column(name = "period_end_date")
    private LocalDate periodEndDate;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Concert(Long venueId, Long sellerId, String title, String status, Instant now) {
        this.venueId = Objects.requireNonNull(venueId);
        this.sellerId = Objects.requireNonNull(sellerId);
        this.title = Objects.requireNonNull(title);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(now);
        this.published = true;
    }

    public static Concert create(Long venueId, Long sellerId, String title, String status, Instant now) {
        return new Concert(venueId, sellerId, title, status, now);
    }

    public static Concert createDraft(Long venueId, Long sellerId, String title, String status, Instant now,
                                      String description, LocalDate periodStartDate, LocalDate periodEndDate,
                                      Integer durationMinutes, Long layoutId) {
        Concert concert = new Concert(venueId, sellerId, title, status, now);
        concert.published = false;
        concert.description = description;
        concert.periodStartDate = periodStartDate;
        concert.periodEndDate = periodEndDate;
        concert.durationMinutes = durationMinutes;
        concert.layoutId = layoutId;
        return concert;
    }

    /** 신규 초안의 공통 배치. 소속·회차 유무는 application 서비스에서 검증한다. */
    public void selectLayout(Long layoutId) {
        requireDraft();
        this.layoutId = Objects.requireNonNull(layoutId);
    }

    /** 공개 준비 검증과 잠금은 application 서비스가 담당한다. */
    public void publish(Instant now) {
        if (!published) {
            publishedAt = Objects.requireNonNull(now);
            published = true;
        }
    }

    private void requireDraft() {
        if (published) throw new IllegalStateException("공개된 공연의 공통 설정은 변경할 수 없습니다.");
    }

    public void changeDetails(String title, String description, LocalDate start, LocalDate end, Integer duration) {
        this.title = title;
        this.description = description;
        this.periodStartDate = start;
        this.periodEndDate = end;
        this.durationMinutes = duration;
    }
}
