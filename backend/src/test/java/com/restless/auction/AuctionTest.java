package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.auction.domain.Auction;
import com.restless.auction.domain.Auction.Terms;
import com.restless.auction.domain.AuctionEntryCondition;
import com.restless.auction.domain.AuctionStatus;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuctionTest {
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    static final Instant START = NOW.plusSeconds(3600);
    static final Instant END = START.plusSeconds(3600);

    static Terms terms(long start, long increment, Instant startsAt, Instant endsAt, int extension) {
        return new Terms("제목", "물품", "설명", start, increment, startsAt, endsAt, extension);
    }

    static Terms valid() {
        return terms(10_000, 1_000, START, END, 60);
    }

    static ErrorCode errorOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    @Test void 등록하면_SCHEDULED이고_기본_종료와_현재_종료가_같다() {
        Auction a = Auction.schedule(7L, valid(), NOW);
        assertThat(a.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        assertThat(a.getAccessPolicy()).isEqualTo(AuctionEntryCondition.ALL);
        assertThat(a.getBaseEndsAt()).isEqualTo(END);
        assertThat(a.getEndsAt()).isEqualTo(END);
        assertThat(a.getRevision()).isZero();
        assertThat(a.isSeller(7L)).isTrue();
        assertThat(a.isSeller(8L)).isFalse();
    }

    @Test void 입력_규칙을_어기면_INVALID_REQUEST() {
        assertThat(errorOf(() -> Auction.schedule(1L, terms(0, 1_000, START, END, 60), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 0, START, END, 60), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, START, END, 29), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, START, END, 301), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, NOW, END, 60), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, START, START, 60), NOW))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, START, END, 30), NOW))).isNull();
        assertThat(errorOf(() -> Auction.schedule(1L, terms(1, 1, START, END, 300), NOW))).isNull();
    }

    @Test void 시작_전에만_수정되고_수정하면_revision이_오른다() {
        Auction a = Auction.schedule(1L, valid(), NOW);
        a.update(terms(20_000, 2_000, START, END.plusSeconds(60), 120), NOW);
        assertThat(a.getStartingPriceAmount()).isEqualTo(20_000);
        assertThat(a.getEndsAt()).isEqualTo(END.plusSeconds(60));
        assertThat(a.getRevision()).isEqualTo(1);

        assertThat(errorOf(() -> a.update(valid(), START))).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED);
        assertThat(errorOf(() -> a.requireNotStarted(START))).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED);
        assertThat(errorOf(() -> a.requireNotStarted(START.minusMillis(1)))).isNull();
    }

    @Test void 최소_입찰_금액은_입찰이_없으면_시작가_있으면_최고가_더하기_인상액() {
        Auction a = Auction.schedule(1L, valid(), NOW);
        assertThat(a.minimumBidAmount(null)).isEqualTo(10_000);
        assertThat(a.minimumBidAmount(15_000L)).isEqualTo(16_000);
    }

    @Test void 연장은_정책_예시대로_종료_시각을_늘리고_줄이지_않는다_INV_AU_10() {
        Instant start = Instant.parse("2026-11-20T00:00:00Z");
        Instant ten = Instant.parse("2026-11-20T10:00:00Z");
        Auction a = Auction.schedule(1L, terms(1, 1, start.plusSeconds(1), ten, 60), start);
        a.acceptBid(Instant.parse("2026-11-20T05:00:00Z"));
        assertThat(a.getEndsAt()).isEqualTo(ten);
        a.acceptBid(Instant.parse("2026-11-20T09:59:30Z"));
        assertThat(a.getEndsAt()).isEqualTo(Instant.parse("2026-11-20T10:00:30Z"));
        a.acceptBid(Instant.parse("2026-11-20T10:00:20Z"));
        assertThat(a.getEndsAt()).isEqualTo(Instant.parse("2026-11-20T10:01:20Z"));
        assertThat(a.getBaseEndsAt()).isEqualTo(ten);
        assertThat(a.getRevision()).isEqualTo(3);
    }

    @Test void 입찰은_시작_시각부터_종료_시각_전까지만() {
        Auction a = Auction.schedule(1L, valid(), NOW);
        assertThat(errorOf(() -> a.requireBiddingOpen(START.minusMillis(1)))).isEqualTo(ErrorCode.AUCTION_NOT_OPEN);
        assertThat(errorOf(() -> a.requireBiddingOpen(START))).isNull();
        assertThat(errorOf(() -> a.requireBiddingOpen(END.minusMillis(1)))).isNull();
        assertThat(errorOf(() -> a.requireBiddingOpen(END))).isEqualTo(ErrorCode.AUCTION_CLOSED);
    }
}
