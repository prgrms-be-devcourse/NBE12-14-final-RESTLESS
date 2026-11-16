package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingOrderStatus;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository.StatusCount;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository.WaitingReserve;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예비 승격과 모집 완료 (transactions.md 10절). 행사 1개 = 트랜잭션 1개.
 *
 * <p>탈퇴 여부는 회원 모듈에 물어야 하므로 잠금을 쥐기 전에 미리 확인한다({@link #preview}).
 * 승격 트랜잭션은 Event FOR UPDATE로 시작하고, <b>빈자리는 행사를 잠근 뒤에 센다.</b> 자리를 바꾸는 다른 처리
 * (결제 결과, 만료, 포기, 0원 확정)는 모두 Event를 공유 잠금으로 쥐므로, 잠근 뒤에 세면 진행 중인 변경이 없다.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingPromotion {

    private static final int MEMBER_LOOKUP_CHUNK = 1000;

    private final FanMeetingEventRepository events;
    private final FanMeetingSelectionRepository selections;
    private final FanMeetingOrderRepository orders;
    private final MemberPort memberPort;
    private final Clock clock;

    /** 미리 확인한 예비: 확인한 선발과, 그중 탈퇴하지 않은 회원. */
    public record Preview(Set<Long> checkedSelectionIds, Set<Long> activeUserIds) {

        static Preview none() {
            return new Preview(Set.of(), Set.of());
        }
    }

    /** 결제 진행 중인 행사를 id 순서로 이어서 찾는다. */
    @Transactional(readOnly = true)
    public List<Long> findInPayment(Long afterId, int limit) {
        return events.findIdsByStatusAfter(FanMeetingStatus.PAYMENT_IN_PROGRESS, afterId, PageRequest.of(0, limit));
    }

    /**
     * 트랜잭션 밖에서 승격 후보(빈자리 × 2명)의 탈퇴 여부를 미리 확인한다. 여기서 센 빈자리는 후보를 몇 명 확인할지
     * 정하는 데만 쓰고, 실제 승격 인원은 {@link #promote}가 행사를 잠근 뒤 다시 센다.
     */
    public Preview preview(Long eventId) {
        Optional<FanMeetingEvent> event = events.findById(eventId);
        if (event.isEmpty()) {
            return Preview.none();
        }
        Map<FanMeetingSelectionStatus, Long> counts = countByStatus(eventId);
        long vacancy = event.get().vacancy(occupied(counts));
        if (vacancy == 0) {
            return Preview.none();
        }
        int size = (int) Math.min(vacancy * 2, Integer.MAX_VALUE);
        List<WaitingReserve> waiting = selections.findWaitingReserves(eventId, PageRequest.of(0, size));
        Set<Long> checked = new HashSet<>();
        List<Long> userIds = new ArrayList<>();
        for (WaitingReserve reserve : waiting) {
            checked.add(reserve.getSelectionId());
            userIds.add(reserve.getUserId());
        }
        Set<Long> active = new HashSet<>();
        for (int from = 0; from < userIds.size(); from += MEMBER_LOOKUP_CHUNK) {
            active.addAll(memberPort.activeMemberIds(
                    userIds.subList(from, Math.min(from + MEMBER_LOOKUP_CHUNK, userIds.size()))));
        }
        return new Preview(checked, active);
    }

    /**
     * 빈자리만큼 예비를 순번대로 승격하고, 모집을 끝낼 때면 끝낸다. 돌려준 알림 요청은 커밋 뒤에 보낸다.
     * 매번 다시 세서 빈자리만큼만 승격하므로 여러 번·동시에 불려도 안전하다.
     */
    @Transactional(timeout = 5)
    public List<NotificationRequest> promote(Long eventId, Preview preview) {
        FanMeetingEvent event = events.findByIdForUpdate(eventId).orElseThrow();
        if (!event.isPaymentInProgress()) {
            return List.of();
        }
        Instant now = clock.instant();
        Map<FanMeetingSelectionStatus, Long> counts = countByStatus(eventId);
        long confirmed = counts.getOrDefault(FanMeetingSelectionStatus.CONFIRMED, 0L);
        long paymentPending = counts.getOrDefault(FanMeetingSelectionStatus.PAYMENT_PENDING, 0L);
        long waiting = counts.getOrDefault(FanMeetingSelectionStatus.WAITING, 0L);
        long vacancy = event.vacancy(confirmed + paymentPending);

        List<NotificationRequest> notifications = new ArrayList<>();
        if (vacancy > 0 && !preview.checkedSelectionIds().isEmpty() && event.isPromotionAllowed(now)) {
            List<FanMeetingSelection> reserves = selections.findWaitingReservesForUpdate(eventId,
                    PageRequest.of(0, preview.checkedSelectionIds().size()));
            for (FanMeetingSelection reserve : reserves) {
                // 미리 확인하지 않은 예비가 나오면 이번 회차는 여기서 멈춘다. 다음 회차가 이어 간다.
                if (vacancy == 0 || !preview.checkedSelectionIds().contains(reserve.getId())) {
                    break;
                }
                waiting--;
                if (!preview.activeUserIds().contains(reserve.getUserId())) {
                    reserve.markNotNeeded(now);
                    continue;
                }
                reserve.promote(now);
                FanMeetingOrder order = orders.save(FanMeetingOrder.issue(reserve, event.getFeeAmount(), now));
                notifications.add(NotificationRequest.promotion(eventId, order.getId(), order.paymentDeadline()));
                paymentPending++;
                vacancy--;
            }
        }

        long review = orders.countByEventIdAndStatus(eventId, FanMeetingOrderStatus.PAYMENT_REVIEW);
        if (event.isRecruitmentDone(new FanMeetingEvent.Occupancy(confirmed, paymentPending, waiting, review), now)) {
            selections.findWaitingReservesForUpdate(eventId, Pageable.unpaged())
                    .forEach(reserve -> reserve.markNotNeeded(now));
            event.completeRecruitment(now);
        }
        return notifications;
    }

    private Map<FanMeetingSelectionStatus, Long> countByStatus(Long eventId) {
        Map<FanMeetingSelectionStatus, Long> counts = new EnumMap<>(FanMeetingSelectionStatus.class);
        for (StatusCount row : selections.countByStatus(eventId)) {
            counts.put(row.getStatus(), row.getCount());
        }
        return counts;
    }

    private static long occupied(Map<FanMeetingSelectionStatus, Long> counts) {
        return counts.getOrDefault(FanMeetingSelectionStatus.CONFIRMED, 0L)
                + counts.getOrDefault(FanMeetingSelectionStatus.PAYMENT_PENDING, 0L);
    }
}
