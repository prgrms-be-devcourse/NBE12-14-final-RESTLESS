package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 팬미팅 불변식을 SQL로 확인한다 (`invariants.md`). 위반한 행을 세는 SQL이 0건이어야 한다.
 * 관통 테스트가 매 단계 뒤에 부른다. 행사 번호를 넘겨 그 행사들만 본다(다른 테스트가 남긴 행은 보지 않는다).
 */
final class FanMeetingInvariants {

    private FanMeetingInvariants() {
    }

    static void assertHolds(JdbcTemplate jdbc, Collection<Long> eventIds, String when) {
        String in = " IN (" + eventIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";

        zero(jdbc, when, "INV-FM-07 확정 + 결제 대기가 정원을 넘음", """
                SELECT COUNT(*) FROM (
                  SELECT e.event_id FROM fan_meeting_events e
                    JOIN fan_meeting_selections s ON s.event_id = e.event_id
                   WHERE e.event_id %s AND s.status IN ('CONFIRMED', 'PAYMENT_PENDING')
                   GROUP BY e.event_id, e.winner_limit HAVING COUNT(*) > e.winner_limit) over_capacity
                """.formatted(in));

        zero(jdbc, when, "INV-FM-08 앞 순번이 기다리는데 뒤 순번이 승격됨", """
                SELECT COUNT(*) FROM fan_meeting_selections w
                 WHERE w.event_id %s AND w.selection_type = 'RESERVE' AND w.status = 'WAITING'
                   AND w.selection_rank < (
                       SELECT MAX(p.selection_rank) FROM fan_meeting_selections p
                        WHERE p.event_id = w.event_id AND p.selection_type = 'RESERVE'
                          AND p.status NOT IN ('WAITING', 'NOT_NEEDED'))
                """.formatted(in));

        zero(jdbc, when, "INV-FM-09 선발–주문 짝 표에 없는 조합", """
                SELECT COUNT(*) FROM fan_meeting_selections s
                  LEFT JOIN fan_meeting_orders o ON o.selection_id = s.selection_id
                 WHERE s.event_id %s AND NOT (
                       (s.status IN ('WAITING', 'NOT_NEEDED') AND o.order_id IS NULL)
                    OR (s.status = 'PAYMENT_PENDING' AND o.status IN ('PENDING_PAYMENT', 'PAYMENT_REVIEW'))
                    OR (s.status = 'CONFIRMED' AND o.status = 'PAID')
                    OR (s.status = 'EXPIRED' AND o.status IN ('EXPIRED', 'REFUND_PENDING', 'REFUNDED'))
                    OR (s.status = 'DECLINED' AND o.status = 'CANCELLED'))
                """.formatted(in));

        zero(jdbc, when, "INV-FM-10 만료된 주문에 승인이 반영됨", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                 WHERE o.event_id %s AND o.status = 'EXPIRED' AND o.approved_attempt_id IS NOT NULL
                """.formatted(in));

        zero(jdbc, when, "INV-FM-10 기한을 넘긴 승인이 결제 완료로 처리됨", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                 WHERE o.event_id %s AND o.status = 'PAID' AND o.paid_at > o.issued_at + INTERVAL 60 MINUTE
                """.formatted(in));

        zero(jdbc, when, "INV-FM-12 주문 금액이 참가비와 다름", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                  JOIN fan_meeting_events e ON e.event_id = o.event_id
                 WHERE o.event_id %s AND o.amount <> e.fee_amount
                """.formatted(in));

        zero(jdbc, when, "INV-FM-14 주문의 행사·회원이 선발과 다름", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
                 WHERE o.event_id %s AND (s.event_id <> o.event_id OR s.user_id <> o.user_id)
                """.formatted(in));

        zero(jdbc, when, "INV-FM-14 선발의 행사·회원이 응모와 다름", """
                SELECT COUNT(*) FROM fan_meeting_selections s
                  JOIN fan_meeting_applications a ON a.application_id = s.application_id
                 WHERE s.event_id %s AND (a.event_id <> s.event_id OR a.user_id <> s.user_id)
                """.formatted(in));

        zero(jdbc, when, "INV-FM-15 모집 완료 행사에 결제 대기·보류·대기 예비가 남음", """
                SELECT COUNT(*) FROM fan_meeting_events e
                 WHERE e.event_id %s AND e.status = 'RECRUITMENT_COMPLETED' AND (
                       EXISTS (SELECT 1 FROM fan_meeting_selections s
                                WHERE s.event_id = e.event_id AND s.status IN ('PAYMENT_PENDING', 'WAITING'))
                    OR EXISTS (SELECT 1 FROM fan_meeting_orders o
                                WHERE o.event_id = e.event_id AND o.status IN ('PENDING_PAYMENT', 'PAYMENT_REVIEW')))
                """.formatted(in));

        // 발급 시각은 UTC로 저장한다. Asia/Seoul은 UTC+9이고 서머타임이 없다.
        zero(jdbc, when, "INV-FM-16 승격 주문의 기한이 승격 마감을 넘거나 야간에 발급됨", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
                  JOIN fan_meeting_events e ON e.event_id = o.event_id
                 WHERE o.event_id %s AND s.selection_type = 'RESERVE' AND (
                       o.issued_at + INTERVAL 60 MINUTE > e.promotion_closes_at
                    OR HOUR(o.issued_at + INTERVAL 9 HOUR) >= 23
                    OR HOUR(o.issued_at + INTERVAL 9 HOUR) < 9)
                """.formatted(in));

        zero(jdbc, when, "INV-FM-17 결제를 준비한 주문이 포기 처리됨", """
                SELECT COUNT(*) FROM fan_meeting_orders o
                  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
                 WHERE o.event_id %s AND s.status = 'DECLINED' AND o.attempt_requested_at IS NOT NULL
                """.formatted(in));
    }

    private static void zero(JdbcTemplate jdbc, String when, String invariant, String sql) {
        assertThat(jdbc.queryForObject(sql, Integer.class)).as("%s (%s)", invariant, when).isZero();
    }
}
