package com.restless.auction.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * 경매 결과. 경매당 하나이고(PK = auction_id), 만든 뒤 바꾸지 않는다 (INV-AU-02).
 * PK를 직접 넣으므로 {@link Persistable}로 새 행임을 알려 save가 바로 INSERT하게 한다.
 */
@Entity
@Table(name = "auction_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuctionResult implements Persistable<Long> {

    @Id
    private Long auctionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private AuctionResultType resultType;

    private Long winningBidId;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant finalizedAt;

    @Transient
    private boolean isNew;

    public static AuctionResult sold(Long auctionId, Long winningBidId, Instant now) {
        return create(auctionId, AuctionResultType.SOLD, Objects.requireNonNull(winningBidId), now);
    }

    public static AuctionResult unsold(Long auctionId, Instant now) {
        return create(auctionId, AuctionResultType.UNSOLD, null, now);
    }

    private static AuctionResult create(Long auctionId, AuctionResultType type, Long winningBidId, Instant now) {
        AuctionResult result = new AuctionResult();
        result.auctionId = Objects.requireNonNull(auctionId);
        result.resultType = type;
        result.winningBidId = winningBidId;
        result.finalizedAt = Objects.requireNonNull(now);
        result.isNew = true;
        return result;
    }

    @Override
    public Long getId() {
        return auctionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
