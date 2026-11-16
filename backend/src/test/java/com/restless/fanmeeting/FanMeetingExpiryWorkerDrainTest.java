package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.fanmeeting.application.FanMeetingExpiryWorker;
import com.restless.fanmeeting.application.FanMeetingOrderExpiry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.time.Instant;
import com.restless.fanmeeting.application.FanMeetingOrderExpiry.Cursor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 만료 Worker는 한 회차에서 대상이 없어질 때까지 묶음(100건)을 반복한다 (`transactions.md` 8절).
 * 당첨자는 모두 같은 시각에 주문이 발급돼 기한도 같이 끝나므로, 한 회차에 한 묶음만 처리하면 승격이 밀린다.
 * DB 없이 조회·처리 호출 횟수만 본다(기본 묶음 크기 100).
 */
@DisplayName("팬미팅 만료 Worker: 한 회차 반복 처리")
class FanMeetingExpiryWorkerDrainTest {

    private static final int BATCH = 100;

    private final FanMeetingOrderExpiry expiry = mock(FanMeetingOrderExpiry.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FanMeetingExpiryWorker worker = new FanMeetingExpiryWorker(expiry, meters);
    private final AtomicLong nextId = new AtomicLong(1);

    @Test
    @DisplayName("만료 대상 250건 → 한 회차에 100, 100, 50건을 모두 처리하고 멈춘다")
    void 대상이_묶음보다_많으면_없어질_때까지_반복한다() {
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(ids(100), ids(100), ids(50));
        when(expiry.expire(anyLong())).thenReturn(true);

        worker.expireDue();

        verify(expiry, times(3)).findDueToExpire(any(), eq(BATCH));
        verify(expiry, times(250)).expire(anyLong());
    }

    @Test
    @DisplayName("대상이 딱 100건 → 다음 묶음이 비어 있는 것을 확인하고 멈춘다 (묶음 크기와 같은 경계)")
    void 묶음이_꽉_찼으면_한_번_더_조회한다() {
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(ids(100), List.of());
        when(expiry.expire(anyLong())).thenReturn(true);

        worker.expireDue();

        verify(expiry, times(2)).findDueToExpire(any(), eq(BATCH));
        verify(expiry, times(100)).expire(anyLong());
    }

    @Test
    @DisplayName("대상이 99건 → 묶음이 덜 찼으므로 다시 조회하지 않는다 (묶음 크기 1건 전 경계)")
    void 묶음이_덜_찼으면_다시_조회하지_않는다() {
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(ids(99));
        when(expiry.expire(anyLong())).thenReturn(true);

        worker.expireDue();

        verify(expiry, times(1)).findDueToExpire(any(), eq(BATCH));
        verify(expiry, times(99)).expire(anyLong());
    }

    @Test
    @DisplayName("묶음 전체가 실패 → 뒤 묶음을 확인하고 끝낸다. 실패는 메트릭으로 남긴다")
    void 한_건도_처리하지_못해도_다음_묶음을_조회한다() {
        List<Cursor> stuck = ids(100);
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(stuck, List.of());
        when(expiry.expire(anyLong())).thenThrow(new IllegalStateException("잠금 시간 초과"));

        worker.expireDue();

        verify(expiry, times(2)).findDueToExpire(any(), eq(BATCH));
        assertThat(meters.counter("fanmeeting.worker.failure", "action", "expire").count()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("묶음 전체가 '만료 대상 아님'(다른 처리가 먼저 끝냄) → 다음 묶음을 조회한다")
    void 처리한_것이_없어도_다음_묶음을_조회한다() {
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(ids(100), List.of());
        when(expiry.expire(anyLong())).thenReturn(false);

        worker.expireDue();

        verify(expiry, times(2)).findDueToExpire(any(), eq(BATCH));
    }

    @Test
    @DisplayName("일부만 실패 → 처리한 주문이 있으면 다음 묶음으로 계속한다")
    void 일부가_실패해도_진행이_있으면_계속한다() {
        List<Cursor> first = ids(100);
        when(expiry.findDueToExpire(any(), eq(BATCH))).thenReturn(first, ids(10));
        when(expiry.expire(anyLong())).thenReturn(true);
        when(expiry.expire(first.get(0).orderId())).thenThrow(new IllegalStateException("잠금 시간 초과"));

        worker.expireDue();

        verify(expiry, times(2)).findDueToExpire(any(), eq(BATCH));
        verify(expiry, times(110)).expire(anyLong());
    }

    @Test
    @DisplayName("대상이 끝없이 나와도 한 회차는 최대 100묶음에서 멈춘다 (다음 회차가 이어 간다)")
    void 한_회차의_반복에는_상한이_있다() {
        when(expiry.findDueToExpire(any(), anyInt())).thenAnswer(call -> ids(100));
        when(expiry.expire(anyLong())).thenReturn(true);

        worker.expireDue();

        verify(expiry, times(FanMeetingExpiryWorker.MAX_ROUNDS)).findDueToExpire(any(), eq(BATCH));
        assertThat(FanMeetingExpiryWorker.MAX_ROUNDS).isEqualTo(100);
    }

    @Test
    @DisplayName("보류 경고도 대상이 없어질 때까지 반복한다")
    void 보류_경고도_반복한다() {
        when(expiry.findReviewsToWarn(any(), eq(BATCH))).thenReturn(ids(100), ids(30));
        when(expiry.warnStaleReview(anyLong())).thenReturn(true);

        worker.warnStaleReviews();

        verify(expiry, times(2)).findReviewsToWarn(any(), eq(BATCH));
        verify(expiry, times(130)).warnStaleReview(anyLong());
    }

    private List<Cursor> ids(int count) {
        return LongStream.range(0, count).mapToObj(i -> new Cursor(nextId.getAndIncrement(), Instant.parse("2026-11-16T00:00:00Z"))).toList();
    }
}
