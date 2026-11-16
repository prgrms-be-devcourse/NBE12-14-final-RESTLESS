-- 공통 결제 callback으로 받아들인 승인 시도의 식별과 미확정 여부를 티켓팅이 보존한다.
-- 기존 결제 이력은 추정해 채우지 않는다. 아직 연결 전인 기존 예약은 NULL/NULL/false다.
ALTER TABLE ticket_reservations
    ADD COLUMN current_payment_id BIGINT NULL,
    ADD COLUMN approval_started_at DATETIME(6) NULL,
    ADD COLUMN payment_result_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_ticket_reservations_current_payment UNIQUE (current_payment_id),
    ADD CONSTRAINT ck_ticket_reservations_payment_attempt CHECK (
        (current_payment_id IS NULL AND approval_started_at IS NULL AND payment_result_pending = FALSE)
        OR (current_payment_id IS NOT NULL AND current_payment_id > 0
            AND approval_started_at IS NOT NULL
            AND order_id IS NOT NULL AND checkout_started_at IS NOT NULL AND payment_expires_at IS NOT NULL
            AND approval_started_at >= checkout_started_at AND approval_started_at < payment_expires_at
            AND status <> 'HELD')
    ),
    ADD CONSTRAINT ck_ticket_reservations_payment_pending_flag CHECK (payment_result_pending IN (0, 1));
