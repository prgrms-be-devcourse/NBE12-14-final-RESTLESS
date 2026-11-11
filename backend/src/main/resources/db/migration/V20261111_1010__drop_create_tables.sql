-- 한정판 드롭 (담당: 이태호)
-- 구매 대기열 테이블은 설계 미정이라 제외했다.

CREATE TABLE drops
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    concert_id  BIGINT       NOT NULL COMMENT '[확인] 원본은 필수. NULL 허용 여부 미정',
    title       VARCHAR(200) NOT NULL,
    description TEXT         NOT NULL,
    image_url   VARCHAR(500) NOT NULL,
    opens_at    DATETIME(6)  NOT NULL,
    closes_at   DATETIME(6)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_drops PRIMARY KEY (id),
    CONSTRAINT fk_drops_concert FOREIGN KEY (concert_id) REFERENCES concerts (id),
    CONSTRAINT ck_drops_period CHECK (opens_at < closes_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drop_variants
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    drop_id      BIGINT       NOT NULL,
    sku_code     VARCHAR(50)  NOT NULL,
    option_name  VARCHAR(100) NOT NULL,
    price_amount BIGINT       NOT NULL,
    is_active    BOOLEAN      NOT NULL,
    CONSTRAINT pk_drop_variants PRIMARY KEY (id),
    CONSTRAINT uk_drop_variants_sku UNIQUE (drop_id, sku_code),
    CONSTRAINT fk_drop_variants_drop FOREIGN KEY (drop_id) REFERENCES drops (id),
    CONSTRAINT ck_drop_variants_price_amount CHECK (price_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- INV-01 재고 보존, INV-02 수량 0 이상을 DB가 마지막으로 지킨다.
CREATE TABLE drop_inventories
(
    variant_id         BIGINT NOT NULL,
    total_quantity     INT    NOT NULL,
    available_quantity INT    NOT NULL,
    reserved_quantity  INT    NOT NULL,
    sold_quantity      INT    NOT NULL,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_drop_inventories PRIMARY KEY (variant_id),
    CONSTRAINT fk_drop_inventories_variant FOREIGN KEY (variant_id) REFERENCES drop_variants (id),
    CONSTRAINT ck_drop_inventories_non_negative CHECK (
        total_quantity >= 0 AND available_quantity >= 0 AND reserved_quantity >= 0 AND sold_quantity >= 0
        ),
    CONSTRAINT ck_drop_inventories_conservation CHECK (
        total_quantity = available_quantity + reserved_quantity + sold_quantity
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drop_reservations
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    drop_id     BIGINT       NOT NULL,
    variant_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    order_id    BIGINT       NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_drop_reservations PRIMARY KEY (id),
    CONSTRAINT uk_drop_reservations_order UNIQUE (order_id),
    CONSTRAINT uk_drop_reservations_request_key UNIQUE (drop_id, user_id, request_key),
    CONSTRAINT fk_drop_reservations_drop FOREIGN KEY (drop_id) REFERENCES drops (id),
    CONSTRAINT fk_drop_reservations_variant FOREIGN KEY (variant_id) REFERENCES drop_variants (id),
    CONSTRAINT fk_drop_reservations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_drop_reservations_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- INV-05 같은 사용자·드롭의 활성 구매 거래는 최대 1건.
CREATE TABLE drop_purchase_slots
(
    drop_id               BIGINT      NOT NULL,
    user_id               BIGINT      NOT NULL,
    active_reservation_id BIGINT      NULL,
    updated_at            DATETIME(6) NOT NULL,
    CONSTRAINT pk_drop_purchase_slots PRIMARY KEY (drop_id, user_id),
    CONSTRAINT fk_drop_purchase_slots_drop FOREIGN KEY (drop_id) REFERENCES drops (id),
    CONSTRAINT fk_drop_purchase_slots_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_drop_purchase_slots_reservation FOREIGN KEY (active_reservation_id)
        REFERENCES drop_reservations (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
