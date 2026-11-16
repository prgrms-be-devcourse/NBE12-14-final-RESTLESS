package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import java.time.Instant;

/**
 * 내 추첨 결과. 추첨이 확정되기 전에는 PENDING만 내보낸다(결과가 미리 새지 않게).
 *
 * @param reserveRank     예비일 때만 본인 순번(1부터). 승격된 뒤에도 남는다
 * @param orderId         주문이 있을 때만(당첨자, 승격된 예비자)
 * @param paymentDeadline 주문이 있을 때만
 */
public record MyFanMeetingResult(Type result, FanMeetingSelectionStatus selectionStatus, Integer reserveRank,
                                 Long orderId, Instant paymentDeadline) {

    public enum Type {
        /** 응모하지 않았거나 취소했다. */
        NOT_APPLIED,
        /** 응모했고 추첨 결과가 아직 확정되지 않았다. */
        PENDING,
        WINNER,
        RESERVE,
        /** 응모했지만 뽑히지 않았다. */
        NOT_SELECTED
    }

    static MyFanMeetingResult of(Type type) {
        return new MyFanMeetingResult(type, null, null, null, null);
    }

    static MyFanMeetingResult selected(FanMeetingSelection selection, FanMeetingOrder order) {
        boolean reserve = selection.isReserve();
        return new MyFanMeetingResult(reserve ? Type.RESERVE : Type.WINNER, selection.getStatus(),
                reserve ? selection.getSelectionRank() : null,
                order == null ? null : order.getId(), order == null ? null : order.paymentDeadline());
    }
}
