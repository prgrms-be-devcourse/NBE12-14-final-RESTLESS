-- 크레딧 충전 주문과 PG 결제 연결 (#316, 담당: 천종원)
-- 충전 주문(credit_charges)은 결제 대상 CREDIT_CHARGE다. 승인되면 내역 CHARGE로 지갑에 지급한다.
-- 기준: docs/domains/auction/policy.md 7절, invariants.md INV-AU-16·17. 결제 테이블의 결제 대상 CHECK도 함께 넓힌다.

CREATE TABLE credit_charges
(
    credit_charge_id BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    amount           BIGINT      NOT NULL,
    status           VARCHAR(20) NOT NULL,
    request_key      VARCHAR(64) NOT NULL,
    paid_payment_id  BIGINT      NULL,
    expires_at       DATETIME(6) NOT NULL,
    paid_at          DATETIME(6) NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT pk_credit_charges PRIMARY KEY (credit_charge_id),
    CONSTRAINT uk_credit_charges_user_request_key UNIQUE (user_id, request_key),
    CONSTRAINT uk_credit_charges_paid_payment UNIQUE (paid_payment_id),
    CONSTRAINT fk_credit_charges_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_credit_charges_amount CHECK (amount BETWEEN 1000 AND 1000000 AND MOD(amount, 1000) = 0),
    CONSTRAINT ck_credit_charges_status CHECK
        ((status = 'READY' AND paid_payment_id IS NULL AND paid_at IS NULL)
            OR (status = 'PAID' AND paid_payment_id IS NOT NULL AND paid_at IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE payments
    DROP CHECK ck_payments_payable_type,
    ADD CONSTRAINT ck_payments_payable_type CHECK (payable_type IN ('DROP', 'TICKET', 'FAN_MEETING', 'CREDIT_CHARGE'));

ALTER TABLE credit_transactions
    DROP CHECK ck_credit_transactions_type,
    ADD CONSTRAINT ck_credit_transactions_type CHECK
        (transaction_type IN ('GRANT', 'HOLD', 'RELEASE', 'CAPTURE', 'CHARGE'));
