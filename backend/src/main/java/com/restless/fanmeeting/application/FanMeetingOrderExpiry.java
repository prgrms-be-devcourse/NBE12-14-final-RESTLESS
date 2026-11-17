package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 만료와 보류 경고 (transactions.md 8절). 주문 1건 = 트랜잭션 1개.
 *
 * <p>결제를 준비한 시도의 결과는 결제 모듈이 Handler로 알려 준다. 여기서는 결제 모듈이 알려 줄 수 없는 주문만 맡는다:
 * 결제 대기, 기한 경과, 진행 중인 시도 없음. 후보는 잠금 없이 찾고, Event FOR SHARE → Order FOR UPDATE →
 * Selection FOR UPDATE로 잠근 뒤 다시 확인한다. 보류 경고는 선발을 바꾸지 않으므로 Order까지만 잠근다.
 * 결제 행은 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingOrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(FanMeetingOrderExpiry.class);

    private final FanMeetingEventRepository events;
    private final FanMeetingOrderRepository orders;
    private final FanMeetingSelectionRepository selections;
    private final Clock clock;

    /** 마지막으로 조회한 주문의 정렬 키. 처리가 실패해도 다음 묶음은 이 위치 뒤에서 읽는다. */
    public record Cursor(Long orderId, Instant position) {
    }

    @Transactional(readOnly = true)
    public List<Cursor> findDueToExpire(Cursor after, int limit) {
        return orders.findIdsToExpire(FanMeetingOrder.issuedAtPastDeadline(clock.instant()),
                after == null ? null : after.position(), after == null ? 0L : after.orderId(),
                PageRequest.of(0, limit)).stream().map(row -> new Cursor(row.getOrderId(), row.getPosition())).toList();
    }

    @Transactional(readOnly = true)
    public List<Cursor> findReviewsToWarn(Cursor after, int limit) {
        return orders.findIdsToWarnReview(FanMeetingOrder.reviewStartedAtToWarn(clock.instant()),
                after == null ? null : after.position(), after == null ? 0L : after.orderId(),
                PageRequest.of(0, limit)).stream().map(row -> new Cursor(row.getOrderId(), row.getPosition())).toList();
    }

    /** 결제 대기, 기한 경과, 진행 중인 시도 없음이면 주문·선발을 EXPIRED로 바꾼다. 만료했으면 true. */
    @Transactional(timeout = 5)
    public boolean expire(Long orderId) {
        Optional<FanMeetingOrder> locked = lockOrder(orderId);
        if (locked.isEmpty()) {
            return false;
        }
        FanMeetingOrder order = locked.get();
        FanMeetingSelection selection = selections.findByIdForUpdate(order.getSelectionId()).orElseThrow();
        Instant now = clock.instant();
        if (!order.isExpirable(now)) {
            return false;
        }
        order.expire(now);
        selection.expire(now);
        return true;
    }

    /**
     * 보류가 30분을 넘었고 이번 보류에서 아직 경고하지 않았으면 경고 로그를 남기고 기록한다. 경고했으면 true.
     * 보류는 자동으로 확정하거나 탈락시키지 않는다(운영자 수동 처리는 P1).
     */
    @Transactional(timeout = 5)
    public boolean warnStaleReview(Long orderId) {
        Optional<FanMeetingOrder> locked = lockOrder(orderId);
        if (locked.isEmpty()) {
            return false;
        }
        FanMeetingOrder order = locked.get();
        Instant now = clock.instant();
        if (!order.isReviewStale(now)) {
            return false;
        }
        log.warn("FANMEETING_PAYMENT_REVIEW_STALE orderId={} elapsedMinutes={} 결제 결과를 {}분 넘게 확인하지 못했습니다.",
                orderId, Duration.between(order.getReviewStartedAt(), now).toMinutes(),
                FanMeetingOrder.reviewWarningMinutes());
        order.markReviewWarned(now);
        return true;
    }

    /** Event FOR SHARE → Order FOR UPDATE. 행사는 잠그기 전에 일반 조회로 찾는다(event_id는 바뀌지 않는다). */
    private Optional<FanMeetingOrder> lockOrder(Long orderId) {
        Optional<Long> eventId = orders.findEventIdById(orderId);
        if (eventId.isEmpty()) {
            return Optional.empty();
        }
        events.findByIdForShare(eventId.get()).orElseThrow();
        return orders.findByIdForUpdate(orderId);
    }
}
