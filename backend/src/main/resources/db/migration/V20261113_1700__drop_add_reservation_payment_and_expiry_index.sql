-- D3 payment attempt ownership; deliberately no physical payment FK.
ALTER TABLE drop_reservations
    ADD COLUMN current_payment_id BIGINT NULL,
    ADD INDEX ix_drop_reservations_expiry (status, expires_at);
