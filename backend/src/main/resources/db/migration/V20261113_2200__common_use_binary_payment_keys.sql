-- Client idempotency keys are opaque: case and accents distinguish different requests.
-- Same rule as drop (V20261113_1701) and ticketing request keys.
ALTER TABLE payments
    MODIFY COLUMN idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE payment_refunds
    MODIFY COLUMN idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN client_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL;
