-- Handler receives the attempt context (paymentId, approval start time) and the refund kind on every state change.
-- Merged V20261112_1000 is not edited.

ALTER TABLE payments
    ADD COLUMN approval_started_at DATETIME(6) NULL,
    -- Approval start records the PG paymentKey and the server time together.
    ADD CONSTRAINT ck_payments_approval_started_with_key CHECK
        ((payment_key IS NULL) = (approval_started_at IS NULL));

-- Customer refund reverses a confirmed purchase. Compensation returns money for an approval the domain rejected.
ALTER TABLE payment_refunds
    ADD COLUMN kind VARCHAR(20) NOT NULL,
    ADD CONSTRAINT ck_payment_refunds_kind CHECK (kind IN ('CUSTOMER_REFUND', 'COMPENSATION'));
