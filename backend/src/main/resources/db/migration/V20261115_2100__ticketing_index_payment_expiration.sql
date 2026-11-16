-- 미확정 승인 시도를 제외한 결제 기한 만료 후보를 기한·ID 순서로 조회한다.
CREATE INDEX idx_ticket_reservations_payment_expiry
    ON ticket_reservations (status, payment_result_pending, payment_expires_at, id);
