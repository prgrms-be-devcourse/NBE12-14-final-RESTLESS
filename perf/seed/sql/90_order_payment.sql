-- 주문, 결제, 환불. 따로 만들지 않고 도메인 배경 데이터에서 파생한다. 핫 이벤트는 테스트가 만든다.
-- 지금은 드롭 배경 점유만 파생한다. 다른 도메인을 붙일 때는 MAX(id) 뒤로 ID를 이어서 쓴다.
--
-- 점유 상태 → 주문, 결제 (docs/domains/drop/state-machines.md 3~5절)
--   CONFIRMED → PAID,      APPROVED
--   RESTORED  → REFUNDED,  CANCELLED + 손님 환불 COMPLETED
--   RELEASED  → CANCELLED, FAILED (승인 시작 뒤 실패가 예약 기한 뒤 확정)
--   EXPIRED   → CANCELLED, FAILED (승인 시작 전 기한 경과로 닫힌 READY)
-- 주문 ID = 결제 ID = 점유 ID. 금액 = SKU 가격 (INV-08).

INSERT INTO orders (id, user_id, order_type, status, total_amount, created_at, updated_at)
SELECT r.order_id,
       r.user_id,
       'DROP',
       CASE r.status
           WHEN 'CONFIRMED' THEN 'PAID'
           WHEN 'RESTORED' THEN 'REFUNDED'
           ELSE 'CANCELLED'
       END,
       v.price_amount,
       r.created_at,
       r.updated_at
FROM drop_reservations r
JOIN drop_variants v ON v.id = r.variant_id
WHERE r.drop_id <> 1;

INSERT INTO order_items (order_id, item_type, item_ref_id, item_name_snapshot, option_name_snapshot,
                         unit_price_snapshot, quantity)
SELECT r.order_id, 'DROP_VARIANT', v.id, d.title, v.option_name, v.price_amount, 1
FROM drop_reservations r
JOIN drop_variants v ON v.id = r.variant_id
JOIN drops d ON d.id = r.drop_id
WHERE r.drop_id <> 1
ORDER BY r.order_id;

INSERT INTO order_shipping_snapshots (order_id, recipient, phone, address)
SELECT r.order_id, CONCAT('perf', r.user_id), '010-0000-0000', 'Seoul'
FROM drop_reservations r
WHERE r.drop_id <> 1;

INSERT INTO payments (id, provider, payment_key, status, amount, approved_at, payable_type, payable_id, user_id,
                      pg_order_id, idempotency_key, expires_at, failure_code, failure_message, created_at,
                      updated_at, approval_started_at)
SELECT r.current_payment_id,
       'TOSS',
       CASE WHEN r.status = 'EXPIRED' THEN NULL ELSE CONCAT('perf-pk-', r.current_payment_id) END,
       CASE r.status
           WHEN 'CONFIRMED' THEN 'APPROVED'
           WHEN 'RESTORED' THEN 'CANCELLED'
           ELSE 'FAILED'
       END,
       o.total_amount,
       CASE WHEN r.status IN ('CONFIRMED', 'RESTORED') THEN r.created_at + INTERVAL 35 SECOND END,
       'DROP',
       r.id,
       r.user_id,
       CONCAT('PAY-PERF-', LPAD(r.current_payment_id, 12, '0')),
       CONCAT('perf-pay-', r.current_payment_id),
       r.expires_at,
       CASE r.status WHEN 'RELEASED' THEN 'PERF_REJECTED' WHEN 'EXPIRED' THEN 'EXPIRED' END,
       CASE r.status WHEN 'RELEASED' THEN 'perf seed rejected' WHEN 'EXPIRED' THEN 'perf seed expired' END,
       r.created_at + INTERVAL 10 SECOND,
       r.updated_at,
       CASE r.status
           WHEN 'EXPIRED' THEN NULL
           WHEN 'RELEASED' THEN r.created_at + INTERVAL 4 MINUTE
           ELSE r.created_at + INTERVAL 30 SECOND
       END
FROM drop_reservations r
JOIN orders o ON o.id = r.order_id
WHERE r.drop_id <> 1;

INSERT INTO payment_refunds (payment_id, status, amount, requested_at, idempotency_key, cancel_transaction_key,
                             reason, completed_at, updated_at, kind, client_key)
SELECT p.id,
       'COMPLETED',
       p.amount,
       p.updated_at - INTERVAL 10 MINUTE,
       CONCAT('perf-refund-', p.id),
       CONCAT('perf-ctk-', p.id),
       'perf seed refund',
       p.updated_at,
       p.updated_at,
       'CUSTOMER_REFUND',
       CONCAT('perf-refund-client-', p.id)
FROM payments p
WHERE p.status = 'CANCELLED'
ORDER BY p.id;
