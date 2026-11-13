-- Preserve provider idempotency keys for in-flight cancellation retries.
-- Customer request replay is scoped to a payment and separated from compensation keys.
ALTER TABLE payment_refunds ADD COLUMN client_key VARCHAR(64) NULL;

UPDATE payment_refunds SET client_key = idempotency_key WHERE kind = 'CUSTOMER_REFUND';

ALTER TABLE payment_refunds
    ADD CONSTRAINT uk_payment_refunds_client_key UNIQUE (payment_id, kind, client_key);
