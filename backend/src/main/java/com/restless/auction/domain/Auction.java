package com.restless.auction.domain;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
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
 * 경매. 입찰과 종료의 잠금 기준 행이다 (docs/domains/auction/transactions.md).
 * 1차에서는 공연 연결과 티켓 입장 조건을 쓰지 않는다.
 */
@Entity
@Table(name = "auctions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auction {

    public static final int DEFAULT_EXTENSION_SECONDS = 60;
    public static final int MIN_EXTENSION_SECONDS = 30;
    public static final int MAX_EXTENSION_SECONDS = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auction_id")
    private Long id;

    private Long sellerUserId;

    private Long performanceId;

    private String title;

    private String itemName;

    private String itemDescription;

    private long startingPriceAmount;

    private long minimumIncrementAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "access_policy")
    private AuctionEntryCondition accessPolicy;

    private Long requiredPerformanceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private AuctionStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant startsAt;

    /** 처음 정한 종료 시각. 연장해도 바뀌지 않는다. */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant baseEndsAt;

    /** 현재 종료 시각. 입찰마다 늘어날 수 있다 (줄지 않음, INV-AU-10). */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant endsAt;

    private int extensionDurationSeconds;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant closedAt;

    /** 바뀔 때마다 1 증가. 실시간 전달의 순서 번호로 쓴다. */
    private long revision;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant updatedAt;

    /** 경매 등록. 상태는 SCHEDULED, 종료 시각은 기본·현재 모두 입력값이다. */
    public static Auction schedule(Long sellerUserId, Terms terms, Instant now) {
        Auction auction = new Auction();
        auction.sellerUserId = Objects.requireNonNull(sellerUserId);
        auction.accessPolicy = AuctionEntryCondition.ALL;
        auction.status = AuctionStatus.SCHEDULED;
        auction.revision = 0;
        auction.createdAt = Objects.requireNonNull(now);
        auction.apply(terms, now);
        return auction;
    }

    /** 시작 전 수정. 등록 때 받은 항목을 모두 바꾼다. */
    public void update(Terms terms, Instant now) {
        requireNotStarted(now);
        apply(terms, now);
        revision++;
    }

    /** 시작 전인지 확인한다. 시작했으면 AUCTION_ALREADY_STARTED. */
    public void requireNotStarted(Instant now) {
        if (!now.isBefore(startsAt)) {
            throw new BusinessException(ErrorCode.AUCTION_ALREADY_STARTED);
        }
    }

    /**
     * 입찰을 받을 수 있는 시각인지 확인한다. 상태가 아니라 시각으로 판단한다 (INV-AU-11).
     * 시작 전이면 AUCTION_NOT_OPEN, 종료 시각이 지났거나 이미 종료 처리됐으면 AUCTION_CLOSED.
     */
    public void requireBiddingOpen(Instant now) {
        if (status == AuctionStatus.CLOSED || !now.isBefore(endsAt)) {
            throw new BusinessException(ErrorCode.AUCTION_CLOSED);
        }
        if (now.isBefore(startsAt)) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_OPEN);
        }
    }

    /**
     * 입찰이 수락됐을 때 종료 시각을 늘린다: max(현재 종료, 수락 시각 + 연장 시간).
     * 종료 시각은 줄지 않는다 (INV-AU-10). 바뀐 것이 없어도 새 최고 입찰이 생겼으므로 revision은 오른다.
     */
    public void acceptBid(Instant acceptedAt) {
        Instant extended = acceptedAt.plusSeconds(extensionDurationSeconds);
        if (extended.isAfter(endsAt)) {
            endsAt = extended;
        }
        revision++;
        updatedAt = acceptedAt;
    }

    /** 시작 시각이 지난 예정 경매를 진행 중으로 바꾼다. 바꿨으면 true. 종료 시각까지 지났으면 바꾸지 않는다 (종료가 처리). */
    public boolean openIfDue(Instant now) {
        if (status != AuctionStatus.SCHEDULED || now.isBefore(startsAt) || !now.isBefore(endsAt)) {
            return false;
        }
        status = AuctionStatus.OPEN;
        revision++;
        updatedAt = now;
        return true;
    }

    /** 종료 시각이 지났고 아직 종료되지 않았는지. 종료 트랜잭션은 잠금 뒤 이것으로 다시 확인한다. */
    public boolean isDueToClose(Instant now) {
        return status != AuctionStatus.CLOSED && !now.isBefore(endsAt);
    }

    /** 종료 처리. 결과 저장과 크레딧 차감은 같은 트랜잭션에서 서비스가 한다. */
    public void close(Instant now) {
        if (!isDueToClose(now)) {
            throw new IllegalStateException("Auction is not due to close");
        }
        status = AuctionStatus.CLOSED;
        closedAt = now;
        revision++;
        updatedAt = now;
    }

    public boolean isClosed() {
        return status == AuctionStatus.CLOSED;
    }

    public boolean isSeller(Long userId) {
        return sellerUserId.equals(userId);
    }

    /** 다음 입찰의 최소 금액. 입찰이 없으면 시작가, 있으면 최고가 + 최소 인상액. */
    public long minimumBidAmount(Long highestBidAmount) {
        return highestBidAmount == null ? startingPriceAmount : highestBidAmount + minimumIncrementAmount;
    }

    private void apply(Terms terms, Instant now) {
        terms.validate(now);
        title = terms.title();
        itemName = terms.itemName();
        itemDescription = terms.itemDescription();
        startingPriceAmount = terms.startingPriceAmount();
        minimumIncrementAmount = terms.minimumIncrementAmount();
        startsAt = terms.startsAt();
        baseEndsAt = terms.endsAt();
        endsAt = terms.endsAt();
        extensionDurationSeconds = terms.extensionDurationSeconds();
        updatedAt = now;
    }

    /** 판매자가 정하는 경매 조건. */
    public record Terms(String title, String itemName, String itemDescription, long startingPriceAmount,
                        long minimumIncrementAmount, Instant startsAt, Instant endsAt,
                        int extensionDurationSeconds) {

        void validate(Instant now) {
            if (startingPriceAmount < 1 || minimumIncrementAmount < 1) {
                throw invalid("시작가와 최소 인상액은 1 이상이어야 합니다.");
            }
            if (extensionDurationSeconds < MIN_EXTENSION_SECONDS || extensionDurationSeconds > MAX_EXTENSION_SECONDS) {
                throw invalid("연장 시간은 30초에서 300초 사이여야 합니다.");
            }
            if (startsAt == null || endsAt == null || !startsAt.isAfter(now)) {
                throw invalid("시작 시각은 현재보다 뒤여야 합니다.");
            }
            if (!startsAt.isBefore(endsAt)) {
                throw invalid("종료 시각은 시작 시각보다 뒤여야 합니다.");
            }
        }

        private static BusinessException invalid(String message) {
            return new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }
}
