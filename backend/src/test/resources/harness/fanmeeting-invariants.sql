-- 위반 행 수를 반환한다. 0이면 정상이다. 기본은 DB 전체 검사다.
-- event-scope 주석은 테스트에서만 행사 ID 조건으로 치환한다.
-- 가입 검사 ON 데이터만 확인하려면 같은 mysql 세션에서 SET @fanmeeting_joined_at_check = 1; 을 먼저 실행한다.

-- name: INV-FM-01 행사·회원당 응모 최대 1개
SELECT COUNT(*) FROM (
SELECT a.event_id, a.user_id FROM fan_meeting_applications a
WHERE /* event-scope:a */ TRUE
GROUP BY a.event_id, a.user_id HAVING COUNT(*) > 1) violations;

-- name: INV-FM-02 마감 뒤 응모 수정
SELECT COUNT(*) FROM fan_meeting_applications a
JOIN fan_meeting_events e ON e.event_id = a.event_id
WHERE /* event-scope:a */ TRUE AND e.closed_at IS NOT NULL AND a.updated_at > e.closed_at;

-- name: INV-FM-03 가입 시각 검사 ON
SELECT COUNT(*) FROM fan_meeting_applications a
JOIN fan_meeting_events e ON e.event_id = a.event_id
JOIN users u ON u.id = a.user_id
WHERE /* event-scope:a */ TRUE AND COALESCE(@fanmeeting_joined_at_check, 0) = 1
AND u.created_at >= e.first_notice_scheduled_at;

-- name: INV-FM-06 선발 유형별 인원 상한
SELECT COUNT(*) FROM (
SELECT e.event_id FROM fan_meeting_events e JOIN fan_meeting_selections s ON s.event_id = e.event_id
WHERE /* event-scope:e */ TRUE
GROUP BY e.event_id, e.winner_limit, e.reserve_limit
HAVING SUM(s.selection_type = 'WINNER') > e.winner_limit
OR SUM(s.selection_type = 'RESERVE') > e.reserve_limit) violations;

-- name: INV-FM-07 확정 + 결제 대기가 정원을 넘음
SELECT COUNT(*) FROM (
  SELECT e.event_id FROM fan_meeting_events e
    JOIN fan_meeting_selections s ON s.event_id = e.event_id
   WHERE /* event-scope:e */ TRUE AND s.status IN ('CONFIRMED', 'PAYMENT_PENDING')
   GROUP BY e.event_id, e.winner_limit HAVING COUNT(*) > e.winner_limit) over_capacity;

-- name: INV-FM-08 앞 순번이 기다리는데 뒤 순번이 승격됨
SELECT COUNT(*) FROM fan_meeting_selections w
 WHERE /* event-scope:w */ TRUE AND w.selection_type = 'RESERVE' AND w.status = 'WAITING'
   AND w.selection_rank < (
       SELECT MAX(p.selection_rank) FROM fan_meeting_selections p
        WHERE p.event_id = w.event_id AND p.selection_type = 'RESERVE'
          AND p.status NOT IN ('WAITING', 'NOT_NEEDED'));

-- name: INV-FM-09 선발–주문 짝 표에 없는 조합
SELECT COUNT(*) FROM fan_meeting_selections s
  LEFT JOIN fan_meeting_orders o ON o.selection_id = s.selection_id
 WHERE /* event-scope:s */ TRUE AND NOT COALESCE((
       (s.status IN ('WAITING', 'NOT_NEEDED') AND o.order_id IS NULL)
    OR (s.status = 'PAYMENT_PENDING' AND o.status IN ('PENDING_PAYMENT', 'PAYMENT_REVIEW'))
    OR (s.status = 'CONFIRMED' AND o.status = 'PAID')
    OR (s.status = 'EXPIRED' AND o.status IN ('EXPIRED', 'REFUND_PENDING', 'REFUNDED'))
    OR (s.status = 'DECLINED' AND o.status = 'CANCELLED')), FALSE);

-- name: INV-FM-10 만료된 주문에 승인이 반영됨
SELECT COUNT(*) FROM fan_meeting_orders o
 WHERE /* event-scope:o */ TRUE AND o.status = 'EXPIRED' AND o.approved_attempt_id IS NOT NULL;

-- name: INV-FM-10 기한을 넘긴 승인이 결제 완료로 처리됨
SELECT COUNT(*) FROM fan_meeting_orders o
 WHERE /* event-scope:o */ TRUE AND o.status = 'PAID' AND o.paid_at > o.issued_at + INTERVAL 60 MINUTE;

-- name: INV-FM-12 주문 금액이 참가비와 다름
SELECT COUNT(*) FROM fan_meeting_orders o
  JOIN fan_meeting_events e ON e.event_id = o.event_id
 WHERE /* event-scope:o */ TRUE AND o.amount <> e.fee_amount;

-- name: INV-FM-14 주문의 행사·회원이 선발과 다름
SELECT COUNT(*) FROM fan_meeting_orders o
  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
 WHERE /* event-scope:o */ TRUE AND (s.event_id <> o.event_id OR s.user_id <> o.user_id);

-- name: INV-FM-14 선발의 행사·회원이 응모와 다름
SELECT COUNT(*) FROM fan_meeting_selections s
  JOIN fan_meeting_applications a ON a.application_id = s.application_id
 WHERE /* event-scope:s */ TRUE AND (a.event_id <> s.event_id OR a.user_id <> s.user_id);

-- name: INV-FM-15 모집 완료 행사에 결제 대기·보류·대기 예비가 남음
SELECT COUNT(*) FROM fan_meeting_events e
 WHERE /* event-scope:e */ TRUE AND e.status = 'RECRUITMENT_COMPLETED' AND (
       EXISTS (SELECT 1 FROM fan_meeting_selections s
                WHERE s.event_id = e.event_id AND s.status IN ('PAYMENT_PENDING', 'WAITING'))
    OR EXISTS (SELECT 1 FROM fan_meeting_orders o
                WHERE o.event_id = e.event_id AND o.status IN ('PENDING_PAYMENT', 'PAYMENT_REVIEW')));

-- name: INV-FM-16 승격 주문의 기한이 승격 마감을 넘거나 야간에 발급됨
SELECT COUNT(*) FROM fan_meeting_orders o
  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
  JOIN fan_meeting_events e ON e.event_id = o.event_id
 WHERE /* event-scope:o */ TRUE AND s.selection_type = 'RESERVE' AND (
       o.issued_at + INTERVAL 60 MINUTE > e.promotion_closes_at
    OR HOUR(o.issued_at + INTERVAL 9 HOUR) >= 23
    OR HOUR(o.issued_at + INTERVAL 9 HOUR) < 9);

-- name: INV-FM-17 결제를 준비한 주문이 포기 처리됨
SELECT COUNT(*) FROM fan_meeting_orders o
  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
 WHERE /* event-scope:o */ TRUE AND s.status = 'DECLINED' AND o.attempt_requested_at IS NOT NULL;

-- name: INV-FM-18 취소 행사에 하위 데이터 없음
SELECT COUNT(*) FROM fan_meeting_events e
WHERE /* event-scope:e */ TRUE AND e.status = 'CANCELLED' AND (
    EXISTS (SELECT 1 FROM fan_meeting_applications a WHERE a.event_id = e.event_id)
 OR EXISTS (SELECT 1 FROM fan_meeting_draw_runs d WHERE d.event_id = e.event_id)
 OR EXISTS (SELECT 1 FROM fan_meeting_selections s WHERE s.event_id = e.event_id)
 OR EXISTS (SELECT 1 FROM fan_meeting_orders o WHERE o.event_id = e.event_id));
