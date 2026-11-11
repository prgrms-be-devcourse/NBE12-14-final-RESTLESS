-- 팬미팅 추첨 (담당: 홍승연)
-- users, payments로 가는 물리 FK는 두지 않는다 (모듈 경계).
-- 복합 FK로 같은 이벤트, 같은 사용자끼리만 연결되게 한다.
--   selections -> draw_runs     (draw_run_id, event_id)
--   selections -> applications  (application_id, event_id, user_id)
--   orders     -> selections    (selection_id, event_id, user_id)
-- 복합 FK가 참조하는 컬럼 묶음에는 UNIQUE를 둔다 (MySQL은 참조 대상에 인덱스가 필요하다).

CREATE TABLE fan_meeting_events
(
    event_id                  BIGINT       NOT NULL AUTO_INCREMENT,
    title                     VARCHAR(200) NOT NULL,
    status                    VARCHAR(30)  NOT NULL,
    entry_opens_at            DATETIME(6)  NOT NULL,
    entry_closes_at           DATETIME(6)  NOT NULL,
    first_notice_scheduled_at DATETIME(6)  NOT NULL,
    winner_limit              INT          NOT NULL,
    reserve_limit             INT          NOT NULL,
    fee_amount                BIGINT       NOT NULL,
    meeting_at                DATETIME(6)  NOT NULL,
    promotion_closes_at       DATETIME(6)  NOT NULL,
    closed_at                 DATETIME(6)  NULL,
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    CONSTRAINT pk_fan_meeting_events PRIMARY KEY (event_id),
    CONSTRAINT ck_fan_meeting_events_entry_period CHECK (entry_opens_at < entry_closes_at),
    CONSTRAINT ck_fan_meeting_events_limits CHECK (winner_limit > 0 AND reserve_limit >= 0),
    CONSTRAINT ck_fan_meeting_events_fee_amount CHECK (fee_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 이벤트당 추첨 실행은 최대 1건 (event_id UNIQUE).
CREATE TABLE fan_meeting_draw_runs
(
    draw_run_id       BIGINT       NOT NULL AUTO_INCREMENT,
    event_id          BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    random_seed       BINARY(32)   NOT NULL COMMENT '[확인] 길이',
    algorithm_version VARCHAR(20)  NOT NULL,
    candidate_count   INT          NULL,
    lease_token       CHAR(36)     NULL,
    lease_until       DATETIME(6)  NULL,
    attempt_count     INT          NOT NULL DEFAULT 0,
    started_at        DATETIME(6)  NULL,
    completed_at      DATETIME(6)  NULL,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT pk_fan_meeting_draw_runs PRIMARY KEY (draw_run_id),
    CONSTRAINT uk_fan_meeting_draw_runs_event UNIQUE (event_id),
    CONSTRAINT uk_fan_meeting_draw_runs_id_event UNIQUE (draw_run_id, event_id),
    CONSTRAINT fk_fan_meeting_draw_runs_event FOREIGN KEY (event_id) REFERENCES fan_meeting_events (event_id),
    CONSTRAINT ck_fan_meeting_draw_runs_attempt_count CHECK (attempt_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fan_meeting_applications
(
    application_id BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL COMMENT 'users 논리 참조 (물리 FK 없음)',
    status         VARCHAR(20) NOT NULL,
    applied_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT pk_fan_meeting_applications PRIMARY KEY (application_id),
    CONSTRAINT uk_fan_meeting_applications_event_user UNIQUE (event_id, user_id),
    CONSTRAINT uk_fan_meeting_applications_id_event_user UNIQUE (application_id, event_id, user_id),
    CONSTRAINT fk_fan_meeting_applications_event FOREIGN KEY (event_id) REFERENCES fan_meeting_events (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fan_meeting_selections
(
    selection_id   BIGINT      NOT NULL AUTO_INCREMENT,
    event_id       BIGINT      NOT NULL,
    draw_run_id    BIGINT      NOT NULL,
    application_id BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    selection_type VARCHAR(20) NOT NULL,
    selection_rank INT         NOT NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT pk_fan_meeting_selections PRIMARY KEY (selection_id),
    CONSTRAINT uk_fan_meeting_selections_application UNIQUE (application_id),
    CONSTRAINT uk_fan_meeting_selections_rank UNIQUE (draw_run_id, selection_type, selection_rank),
    CONSTRAINT uk_fan_meeting_selections_id_event_user UNIQUE (selection_id, event_id, user_id),
    CONSTRAINT fk_fan_meeting_selections_draw_run FOREIGN KEY (draw_run_id, event_id)
        REFERENCES fan_meeting_draw_runs (draw_run_id, event_id),
    CONSTRAINT fk_fan_meeting_selections_application FOREIGN KEY (application_id, event_id, user_id)
        REFERENCES fan_meeting_applications (application_id, event_id, user_id),
    CONSTRAINT ck_fan_meeting_selections_rank CHECK (selection_rank > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE fan_meeting_orders
(
    order_id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id             BIGINT       NOT NULL,
    selection_id         BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    amount               BIGINT       NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    issued_at            DATETIME(6)  NOT NULL,
    payment_id           BIGINT       NULL COMMENT 'payments 논리 참조 (물리 FK 없음)',
    attempt_requested_at DATETIME(6)  NULL,
    approved_attempt_id  VARCHAR(100) NULL,
    paid_at              DATETIME(6)  NULL,
    review_started_at    DATETIME(6)  NULL,
    review_warned_at     DATETIME(6)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_fan_meeting_orders PRIMARY KEY (order_id),
    CONSTRAINT uk_fan_meeting_orders_selection UNIQUE (selection_id),
    CONSTRAINT uk_fan_meeting_orders_payment UNIQUE (payment_id),
    CONSTRAINT fk_fan_meeting_orders_selection FOREIGN KEY (selection_id, event_id, user_id)
        REFERENCES fan_meeting_selections (selection_id, event_id, user_id),
    CONSTRAINT ck_fan_meeting_orders_amount CHECK (amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
