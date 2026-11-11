-- 크레딧 (담당: 천종원, 공통 모듈 여부 미정)
-- credit_holds.auction_bid_id가 auction_bids를 참조하므로 경매 테이블 다음에 만든다.

-- 사용자당 지갑은 하나 (user_id UNIQUE).
CREATE TABLE credit_wallets
(
    credit_wallet_id BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    balance_amount   BIGINT      NOT NULL,
    reserved_amount  BIGINT      NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT pk_credit_wallets PRIMARY KEY (credit_wallet_id),
    CONSTRAINT uk_credit_wallets_user UNIQUE (user_id),
    CONSTRAINT fk_credit_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_credit_wallets_amounts CHECK (balance_amount >= 0 AND reserved_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 입찰 하나에 묶이는 크레딧은 최대 1건 (auction_bid_id UNIQUE).
CREATE TABLE credit_holds
(
    credit_hold_id   BIGINT      NOT NULL AUTO_INCREMENT,
    credit_wallet_id BIGINT      NOT NULL,
    auction_bid_id   BIGINT      NOT NULL,
    held_amount      BIGINT      NOT NULL,
    status           VARCHAR(20) NOT NULL,
    held_at          DATETIME(6) NOT NULL,
    released_at      DATETIME(6) NULL,
    captured_at      DATETIME(6) NULL,
    CONSTRAINT pk_credit_holds PRIMARY KEY (credit_hold_id),
    CONSTRAINT uk_credit_holds_auction_bid UNIQUE (auction_bid_id),
    CONSTRAINT fk_credit_holds_wallet FOREIGN KEY (credit_wallet_id) REFERENCES credit_wallets (credit_wallet_id),
    CONSTRAINT fk_credit_holds_auction_bid FOREIGN KEY (auction_bid_id) REFERENCES auction_bids (auction_bid_id),
    CONSTRAINT ck_credit_holds_held_amount CHECK (held_amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 같은 작업이 두 번 기록되지 않게 operation_key UNIQUE.
CREATE TABLE credit_transactions
(
    credit_transaction_id BIGINT       NOT NULL AUTO_INCREMENT,
    credit_wallet_id      BIGINT       NOT NULL,
    credit_hold_id        BIGINT       NULL,
    transaction_type      VARCHAR(20)  NOT NULL,
    balance_delta         BIGINT       NOT NULL,
    reserved_delta        BIGINT       NOT NULL,
    balance_after         BIGINT       NOT NULL,
    reserved_after        BIGINT       NOT NULL,
    operation_key         VARCHAR(100) NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_credit_transactions PRIMARY KEY (credit_transaction_id),
    CONSTRAINT uk_credit_transactions_operation_key UNIQUE (operation_key),
    CONSTRAINT fk_credit_transactions_wallet FOREIGN KEY (credit_wallet_id) REFERENCES credit_wallets (credit_wallet_id),
    CONSTRAINT fk_credit_transactions_hold FOREIGN KEY (credit_hold_id) REFERENCES credit_holds (credit_hold_id),
    CONSTRAINT ck_credit_transactions_after CHECK (balance_after >= 0 AND reserved_after >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
