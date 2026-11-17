-- SET @performance = <실험 회차 ID>; 를 먼저 실행한다. 각 violations 값은 0이어야 한다.
SELECT 'performance_missing' AS invariant_name, IF(COUNT(*)=1, 0, 1) AS violations FROM performances WHERE id=@performance;
SELECT 'seat_pointer' AS invariant_name, COUNT(*) AS violations FROM performance_seats s LEFT JOIN seat_holds h ON h.id=s.current_hold_id WHERE s.performance_id=@performance AND ((s.status='AVAILABLE' AND s.current_hold_id IS NOT NULL) OR (s.status IN ('HELD','SOLD') AND (h.id IS NULL OR h.performance_seat_id<>s.id OR h.performance_id<>s.performance_id)));
SELECT 'multiple_active_holds', COUNT(*) FROM (SELECT h.performance_seat_id FROM seat_holds h JOIN ticket_reservations r ON r.id=h.reservation_id WHERE h.performance_id=@performance AND h.status='HELD' AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING') GROUP BY h.performance_seat_id HAVING COUNT(*)>1) x;
SELECT 'active_reservations', COUNT(*) FROM (SELECT user_id FROM ticket_reservations WHERE performance_id=@performance AND status IN ('HELD','PAYMENT_PENDING','RECONCILING') GROUP BY user_id HAVING COUNT(*)>1) x;
SELECT 'limits', COUNT(*) FROM ticket_purchase_limits l JOIN performances p ON p.id=l.performance_id WHERE l.performance_id=@performance AND (l.reserved_count<0 OR l.confirmed_count<0 OR l.reserved_count+l.confirmed_count>p.max_tickets_per_user);
SELECT 'held_counts', COUNT(*) FROM ticket_purchase_limits l WHERE l.performance_id=@performance AND l.reserved_count<>(SELECT COUNT(*) FROM ticket_reservations r JOIN seat_holds h ON h.reservation_id=r.id WHERE r.performance_id=l.performance_id AND r.user_id=l.user_id AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING') AND h.status='HELD');
SELECT 'holds_without_owner', COUNT(*) FROM seat_holds h JOIN ticket_reservations r ON r.id=h.reservation_id JOIN performance_seats s ON s.id=h.performance_seat_id WHERE h.performance_id=@performance AND h.status='HELD' AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING') AND (s.current_hold_id IS NULL OR s.current_hold_id<>h.id OR s.status<>'HELD');
-- 빈 회차에서 위반 0건을 통과로 잘못 해석하지 않는다.
SELECT 'performance_no_seats', IF(COUNT(*) > 0, 0, 1) FROM performance_seats WHERE performance_id=@performance;
-- 현재 포인터의 상태는 예약 및 좌석과 일치해야 한다. 환불 진행 중에도 SOLD/CONFIRMED를 보존한다.
SELECT 'owner_status_mismatch', COUNT(*)
FROM performance_seats s
LEFT JOIN seat_holds h ON h.id=s.current_hold_id
LEFT JOIN ticket_reservations r ON r.id=h.reservation_id
WHERE s.performance_id=@performance AND (
    (s.status='HELD' AND (h.id IS NULL OR h.status<>'HELD' OR r.id IS NULL OR r.status NOT IN ('HELD','PAYMENT_PENDING','RECONCILING')))
 OR (s.status='SOLD' AND (h.id IS NULL OR h.status<>'CONFIRMED' OR r.id IS NULL OR r.status NOT IN ('CONFIRMED','REFUND_PENDING','REFUND_RECONCILING')))
);
-- 활성 예약은 하나 이상의 선점을 가져야 하고 각 선점은 해당 좌석의 현재 소유자여야 한다.
SELECT 'active_reservation_without_holds', COUNT(*) FROM ticket_reservations r
WHERE r.performance_id=@performance AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING')
  AND NOT EXISTS (SELECT 1 FROM seat_holds h WHERE h.reservation_id=r.id);
SELECT 'active_reservation_incomplete_holds', COUNT(*)
FROM seat_holds h JOIN ticket_reservations r ON r.id=h.reservation_id
LEFT JOIN performance_seats s ON s.id=h.performance_seat_id
WHERE r.performance_id=@performance AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING')
  AND (h.status<>'HELD' OR s.id IS NULL OR s.status<>'HELD' OR s.current_hold_id IS NULL OR s.current_hold_id<>h.id);
-- 수량을 가진 사용자에게 구매 관리 행이 없다면 관리 행 기준 검사에서 누락되므로 따로 찾는다.
SELECT 'missing_purchase_limit', COUNT(*) FROM (
  SELECT r.user_id, r.performance_id FROM ticket_reservations r
  WHERE r.performance_id=@performance AND r.status IN ('HELD','PAYMENT_PENDING','RECONCILING')
  UNION
  SELECT r.user_id, r.performance_id FROM performance_seats s
  JOIN seat_holds h ON h.id=s.current_hold_id JOIN ticket_reservations r ON r.id=h.reservation_id
  WHERE s.performance_id=@performance AND s.status='SOLD'
) owned LEFT JOIN ticket_purchase_limits l ON l.user_id=owned.user_id AND l.performance_id=owned.performance_id
WHERE l.id IS NULL;
-- 확정 수량은 현재 SOLD 좌석 기준이다. 취소 후 과거 CONFIRMED 선점 이력은 세지 않는다.
SELECT 'confirmed_counts', COUNT(*) FROM ticket_purchase_limits l
WHERE l.performance_id=@performance AND l.confirmed_count<>(
  SELECT COUNT(*) FROM performance_seats s JOIN seat_holds h ON h.id=s.current_hold_id
  JOIN ticket_reservations r ON r.id=h.reservation_id
  WHERE s.performance_id=l.performance_id AND s.status='SOLD' AND r.user_id=l.user_id
);
-- 판매 확정은 좌석별 티켓 발급과 같은 트랜잭션에서 커밋된다.
SELECT 'sold_seat_without_ticket', COUNT(*) FROM performance_seats s
JOIN seat_holds h ON h.id=s.current_hold_id
LEFT JOIN tickets t ON t.reservation_id=h.reservation_id AND t.performance_seat_id=s.id
WHERE s.performance_id=@performance AND s.status='SOLD' AND t.id IS NULL;
