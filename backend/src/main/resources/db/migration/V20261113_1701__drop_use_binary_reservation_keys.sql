-- Request keys are opaque: case and accents distinguish different requests (INV-07).
ALTER TABLE drop_reservations
    MODIFY COLUMN request_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
