-- Tables are empty. Preserve the merged migration and evolve them with ALTER only.
ALTER TABLE payments
    DROP FOREIGN KEY fk_payments_order,
    DROP COLUMN order_id,
    MODIFY COLUMN payment_key VARCHAR(200) NULL,
    ADD COLUMN payable_type VARCHAR(20) NOT NULL,
    ADD COLUMN payable_id BIGINT NOT NULL,
    ADD COLUMN user_id BIGINT NOT NULL,
    ADD COLUMN pg_order_id VARCHAR(64) NOT NULL,
    ADD COLUMN idempotency_key VARCHAR(64) NOT NULL,
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD COLUMN failure_code VARCHAR(100) NULL,
    ADD COLUMN failure_message VARCHAR(500) NULL,
    ADD COLUMN created_at DATETIME(6) NOT NULL,
    ADD COLUMN updated_at DATETIME(6) NOT NULL,
    ADD COLUMN active_payable_key VARCHAR(40) GENERATED ALWAYS AS
        (CASE WHEN status NOT IN ('FAILED', 'CANCELLED') THEN CONCAT(payable_type, ':', payable_id) END) STORED,
    ADD CONSTRAINT fk_payments_user FOREIGN KEY (user_id) REFERENCES users (id),
    ADD CONSTRAINT ck_payments_payable_type CHECK (payable_type IN ('DROP', 'TICKET', 'FAN_MEETING')),
    ADD CONSTRAINT ck_payments_status CHECK
        (status IN ('READY', 'PROCESSING', 'APPROVED', 'FAILED', 'UNKNOWN', 'CANCEL_PENDING', 'CANCELLED', 'CANCEL_UNKNOWN')),
    ADD CONSTRAINT uk_payments_pg_order_id UNIQUE (pg_order_id),
    ADD CONSTRAINT uk_payments_payable_idempotency UNIQUE (payable_type, payable_id, idempotency_key),
    ADD CONSTRAINT uk_payments_active_payable_key UNIQUE (active_payable_key),
    ADD INDEX idx_payments_status_updated_at (status, updated_at);

ALTER TABLE payment_refunds
    ADD COLUMN idempotency_key VARCHAR(64) NOT NULL,
    ADD COLUMN cancel_transaction_key VARCHAR(64) NULL,
    ADD COLUMN reason VARCHAR(200) NOT NULL,
    ADD COLUMN failure_code VARCHAR(100) NULL,
    ADD COLUMN failure_message VARCHAR(500) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN updated_at DATETIME(6) NOT NULL,
    ADD COLUMN active_payment_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status <> 'FAILED' THEN payment_id END) STORED,
    ADD CONSTRAINT uk_payment_refunds_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT uk_payment_refunds_active_payment_id UNIQUE (active_payment_id),
    ADD CONSTRAINT ck_payment_refunds_status CHECK (status IN ('REQUESTED', 'COMPLETED', 'UNKNOWN', 'FAILED'));
