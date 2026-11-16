CREATE TABLE drop_queue_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    drop_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    entered_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    admitted_at DATETIME(6) NULL,
    admitted_until DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    active_user_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('WAITING', 'ADMITTED') THEN user_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uk_drop_queue_active UNIQUE (drop_id, active_user_id),
    CONSTRAINT fk_drop_queue_drop FOREIGN KEY (drop_id) REFERENCES drops(id),
    CONSTRAINT fk_drop_queue_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_drop_queue_status CHECK (status IN ('WAITING','ADMITTED','CONSUMED','EXPIRED','CLOSED')),
    INDEX ix_drop_queue_admission (drop_id, status, id),
    INDEX ix_drop_queue_member (drop_id, user_id, id),
    INDEX ix_drop_queue_waiting (status, last_seen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
