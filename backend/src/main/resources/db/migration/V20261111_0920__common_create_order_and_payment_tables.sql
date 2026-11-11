-- 주문, 결제 (공통)
-- ticket_reservations, drop_reservations가 orders를 참조하므로 도메인 테이블보다 먼저 만든다.

CREATE TABLE orders
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    order_type   VARCHAR(20) NOT NULL COMMENT '[확인] 티켓팅 사용 여부',
    status       VARCHAR(30) NOT NULL,
    total_amount BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 주문 시점의 상품명, 옵션, 가격을 복사해 둔다. 원본이 바뀌어도 변하지 않는다.
CREATE TABLE order_items
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    order_id             BIGINT       NOT NULL,
    item_type            VARCHAR(30)  NOT NULL,
    item_ref_id          BIGINT       NOT NULL,
    item_name_snapshot   VARCHAR(200) NOT NULL,
    option_name_snapshot VARCHAR(100) NOT NULL,
    unit_price_snapshot  BIGINT       NOT NULL,
    quantity             INT          NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price_snapshot >= 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE order_shipping_snapshots
(
    order_id  BIGINT       NOT NULL,
    recipient VARCHAR(50)  NOT NULL,
    phone     VARCHAR(20)  NOT NULL,
    address   VARCHAR(255) NOT NULL,
    CONSTRAINT pk_order_shipping_snapshots PRIMARY KEY (order_id),
    CONSTRAINT fk_order_shipping_snapshots_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payments
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    provider    VARCHAR(20)  NOT NULL,
    payment_key VARCHAR(200) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    amount      BIGINT       NOT NULL,
    approved_at DATETIME(6)  NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uk_payments_payment_key UNIQUE (payment_key),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE payment_refunds
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id   BIGINT      NOT NULL,
    status       VARCHAR(30) NOT NULL,
    amount       BIGINT      NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_payment_refunds PRIMARY KEY (id),
    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payment_refunds_amount CHECK (amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
