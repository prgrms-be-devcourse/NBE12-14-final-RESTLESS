-- Existing status/updated_at index serves stale approval and cancellation scans.
ALTER TABLE payments
    ADD COLUMN unknown_since_at DATETIME(6) NULL,
    ADD INDEX idx_payments_status_expires_at (status, expires_at);

-- Preserve the earliest available observation for already unresolved attempts.
UPDATE payments SET unknown_since_at = updated_at WHERE status = 'UNKNOWN';
