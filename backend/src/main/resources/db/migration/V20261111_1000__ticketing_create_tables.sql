-- 티켓팅 (담당: 송혜민)
-- performance_seats.current_hold_id와 seat_holds.performance_seat_id가 서로를 참조한다.
-- 두 테이블을 만든 뒤 current_hold_id FK를 마지막에 추가한다.

CREATE TABLE ticket_reservations
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    performance_id      BIGINT       NOT NULL,
    order_id            BIGINT       NULL,
    request_key         VARCHAR(100) NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    hold_expires_at     DATETIME(6)  NOT NULL,
    checkout_started_at DATETIME(6)  NULL,
    payment_expires_at  DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_ticket_reservations PRIMARY KEY (id),
    CONSTRAINT uk_ticket_reservations_order UNIQUE (order_id),
    -- [확인] ERD에는 없음. 같은 요청 재시도로 예약이 중복 생성되지 않게 한다.
    CONSTRAINT uk_ticket_reservations_request_key UNIQUE (user_id, performance_id, request_key),
    CONSTRAINT fk_ticket_reservations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ticket_reservations_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT fk_ticket_reservations_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 좌석 하나에 활성 선점은 하나뿐이다: current_hold_id UNIQUE.
CREATE TABLE performance_seats
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    performance_id  BIGINT      NOT NULL,
    layout_id       BIGINT      NOT NULL,
    venue_seat_id   BIGINT      NOT NULL,
    grade           VARCHAR(20) NOT NULL,
    price           BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    current_hold_id BIGINT      NULL,
    CONSTRAINT pk_performance_seats PRIMARY KEY (id),
    CONSTRAINT uk_performance_seats_seat UNIQUE (performance_id, venue_seat_id),
    CONSTRAINT uk_performance_seats_current_hold UNIQUE (current_hold_id),
    CONSTRAINT fk_performance_seats_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT fk_performance_seats_layout_seat FOREIGN KEY (layout_id, venue_seat_id)
        REFERENCES layout_seats (layout_id, venue_seat_id),
    CONSTRAINT ck_performance_seats_price CHECK (price >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE seat_holds
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id      BIGINT      NOT NULL,
    performance_id      BIGINT      NOT NULL,
    performance_seat_id BIGINT      NOT NULL,
    price_amount        BIGINT      NOT NULL,
    expires_at          DATETIME(6) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    released_at         DATETIME(6) NULL,
    CONSTRAINT pk_seat_holds PRIMARY KEY (id),
    CONSTRAINT fk_seat_holds_reservation FOREIGN KEY (reservation_id) REFERENCES ticket_reservations (id),
    CONSTRAINT fk_seat_holds_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT fk_seat_holds_performance_seat FOREIGN KEY (performance_seat_id) REFERENCES performance_seats (id),
    CONSTRAINT ck_seat_holds_price_amount CHECK (price_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE performance_seats
    ADD CONSTRAINT fk_performance_seats_current_hold
        FOREIGN KEY (current_hold_id) REFERENCES seat_holds (id);

CREATE TABLE tickets
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id      BIGINT      NOT NULL,
    performance_id      BIGINT      NOT NULL,
    performance_seat_id BIGINT      NOT NULL,
    ticket_no           VARCHAR(50) NOT NULL,
    price_amount        BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL,
    issued_at           DATETIME(6) NOT NULL,
    used_at             DATETIME(6) NULL,
    cancelled_at        DATETIME(6) NULL,
    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT uk_tickets_ticket_no UNIQUE (ticket_no),
    CONSTRAINT fk_tickets_reservation FOREIGN KEY (reservation_id) REFERENCES ticket_reservations (id),
    CONSTRAINT fk_tickets_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT fk_tickets_performance_seat FOREIGN KEY (performance_seat_id) REFERENCES performance_seats (id),
    CONSTRAINT ck_tickets_price_amount CHECK (price_amount >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE ticket_purchase_limits
(
    id              BIGINT NOT NULL AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    performance_id  BIGINT NOT NULL,
    reserved_count  INT    NOT NULL,
    confirmed_count INT    NOT NULL,
    CONSTRAINT pk_ticket_purchase_limits PRIMARY KEY (id),
    CONSTRAINT uk_ticket_purchase_limits_user_performance UNIQUE (user_id, performance_id),
    CONSTRAINT fk_ticket_purchase_limits_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ticket_purchase_limits_performance FOREIGN KEY (performance_id) REFERENCES performances (id),
    CONSTRAINT ck_ticket_purchase_limits_counts CHECK (reserved_count >= 0 AND confirmed_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
