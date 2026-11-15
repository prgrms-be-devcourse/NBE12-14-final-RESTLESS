package com.restless.fanmeeting.api.dto;

import com.restless.fanmeeting.application.MyFanMeetingResult;
import java.time.Instant;

/**
 * 내 추첨 결과. result는 NOT_APPLIED, PENDING(추첨 확정 전), WINNER, RESERVE, NOT_SELECTED.
 * 뽑히지 않았으면 나머지 값은 null이다. reserveRank는 예비일 때만 본인 순번.
 */
public record MyResultResponse(String result, String selectionStatus, Integer reserveRank, Long orderId,
                               Instant paymentDeadline) {

    public static MyResultResponse from(MyFanMeetingResult mine) {
        return new MyResultResponse(mine.result().name(),
                mine.selectionStatus() == null ? null : mine.selectionStatus().name(),
                mine.reserveRank(), mine.orderId(), mine.paymentDeadline());
    }
}
