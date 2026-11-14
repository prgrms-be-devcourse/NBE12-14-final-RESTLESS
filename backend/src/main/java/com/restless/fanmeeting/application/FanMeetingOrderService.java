package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingApplication;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 손님이 자기 주문에 하는 일: 결제 포기, 0원 주문 확정, 내 결과·주문 조회 (transactions.md 6·9절).
 * 잠금 순서: Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE. 판정에 쓰는 시각은 잠근 뒤에 읽는다.
 * 다른 회원·다른 행사의 주문은 있는지 알려 주지 않고 ORDER_NOT_FOUND로 답한다.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingOrderService {

    private final FanMeetingEventRepository events;
    private final FanMeetingApplicationRepository applications;
    private final FanMeetingSelectionRepository selections;
    private final FanMeetingOrderRepository orders;
    private final Clock clock;

    /** 결제 포기. 결제 대기이고 결제를 준비한 적 없고 기한 전일 때만. 되돌릴 수 없다. */
    @Transactional(timeout = 5)
    public MyFanMeetingOrder decline(Long userId, Long eventId, Long orderId) {
        Locked locked = lockMine(userId, eventId, orderId);
        FanMeetingOrder order = locked.order();
        Instant now = clock.instant();
        if (!order.isPendingPayment()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        if (order.hasRequestedAttempt()) {
            throw new BusinessException(ErrorCode.DECLINE_NOT_ALLOWED);
        }
        if (order.isPastDeadline(now)) {
            throw new BusinessException(ErrorCode.PAYMENT_WINDOW_EXPIRED);
        }
        order.decline(now);
        locked.selection().decline(now);
        return new MyFanMeetingOrder(order, false);
    }

    /** 0원 주문 확정. 기한 안이면 결제 없이 PAID. 이미 확정한 주문이면 아무것도 바꾸지 않는다. */
    @Transactional(timeout = 5)
    public MyFanMeetingOrder confirmFree(Long userId, Long eventId, Long orderId) {
        Locked locked = lockMine(userId, eventId, orderId);
        FanMeetingOrder order = locked.order();
        if (order.isFree() && order.isPaid()) {
            return new MyFanMeetingOrder(order, false);
        }
        if (!order.isFree() || !order.isPendingPayment() || !locked.event().isPaymentInProgress()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        Instant now = clock.instant();
        if (order.isPastDeadline(now)) {
            throw new BusinessException(ErrorCode.PAYMENT_WINDOW_EXPIRED);
        }
        order.confirmFree(now);
        locked.selection().confirm(now);
        return new MyFanMeetingOrder(order, false);
    }

    /** 내 주문(잠금 없음). 주문이 없으면 ORDER_NOT_FOUND. */
    @Transactional(readOnly = true)
    public MyFanMeetingOrder findMyOrder(Long userId, Long eventId) {
        requireEvent(eventId);
        FanMeetingOrder order = orders.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return new MyFanMeetingOrder(order, order.isDeclinable(clock.instant()));
    }

    /** 내 추첨 결과(잠금 없음). 추첨이 확정되기 전에는 결과를 내보내지 않는다. */
    @Transactional(readOnly = true)
    public MyFanMeetingResult findMyResult(Long userId, Long eventId) {
        FanMeetingEvent event = requireEvent(eventId);
        boolean applied = applications.findByEventIdAndUserId(eventId, userId)
                .map(FanMeetingApplication::isApplied)
                .orElse(false);
        if (!applied) {
            return MyFanMeetingResult.of(MyFanMeetingResult.Type.NOT_APPLIED);
        }
        if (!event.isDrawFinalized()) {
            return MyFanMeetingResult.of(MyFanMeetingResult.Type.PENDING);
        }
        Optional<FanMeetingSelection> selection = selections.findByEventIdAndUserId(eventId, userId);
        if (selection.isEmpty()) {
            return MyFanMeetingResult.of(MyFanMeetingResult.Type.NOT_SELECTED);
        }
        FanMeetingOrder order = orders.findBySelectionId(selection.get().getId()).orElse(null);
        return MyFanMeetingResult.selected(selection.get(), order);
    }

    private FanMeetingEvent requireEvent(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
    }

    /**
     * Event FOR SHARE → Order FOR UPDATE → Selection FOR UPDATE.
     * 주문이 이 행사 것인지는 잠그기 전에 일반 조회로 확인한다. 없는 행을 FOR UPDATE로 찾지 않는다(transactions.md 1절 8번).
     */
    private Locked lockMine(Long userId, Long eventId, Long orderId) {
        FanMeetingEvent event = events.findByIdForShare(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FAN_MEETING_NOT_FOUND));
        boolean inThisEvent = orders.findEventIdById(orderId).map(eventId::equals).orElse(false);
        if (!inThisEvent) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        FanMeetingOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("주문 행이 사라졌습니다: " + orderId));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        FanMeetingSelection selection = selections.findByIdForUpdate(order.getSelectionId())
                .orElseThrow(() -> new IllegalStateException("선발 행이 사라졌습니다: " + order.getSelectionId()));
        return new Locked(event, order, selection);
    }

    private record Locked(FanMeetingEvent event, FanMeetingOrder order, FanMeetingSelection selection) {
    }
}
