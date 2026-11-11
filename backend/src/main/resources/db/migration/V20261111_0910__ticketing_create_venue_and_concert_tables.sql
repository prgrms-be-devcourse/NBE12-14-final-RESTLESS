-- 공연장, 공연, 회차 (담당: 송혜민)
-- 티켓팅, 드롭, 경매가 함께 참조하므로 도메인 테이블보다 먼저 만든다.
-- concerts(공연) 아래 회차가 performances다.

CREATE TABLE venues
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    address    VARCHAR(255) NOT NULL,
    timezone   VARCHAR(50)  NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_venues PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE venue_sections
(
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    venue_id BIGINT      NOT NULL,
    code     VARCHAR(30) NOT NULL,
    name     VARCHAR(50) NOT NULL,
    floor    VARCHAR(20) NOT NULL,
    status   VARCHAR(20) NOT NULL,
    CONSTRAINT pk_venue_sections PRIMARY KEY (id),
    CONSTRAINT uk_venue_sections_venue_code UNIQUE (venue_id, code),
    CONSTRAINT fk_venue_sections_venue FOREIGN KEY (venue_id) REFERENCES venues (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE venue_seats
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    venue_id      BIGINT      NOT NULL,
    section_id    BIGINT      NOT NULL,
    row_label     VARCHAR(10) NOT NULL,
    seat_number   VARCHAR(10) NOT NULL,
    is_accessible BOOLEAN     NOT NULL,
    status        VARCHAR(20) NOT NULL,
    CONSTRAINT pk_venue_seats PRIMARY KEY (id),
    CONSTRAINT uk_venue_seats_position UNIQUE (section_id, row_label, seat_number),
    CONSTRAINT fk_venue_seats_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT fk_venue_seats_section FOREIGN KEY (section_id) REFERENCES venue_sections (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE venue_layouts
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    venue_id     BIGINT      NOT NULL,
    layout_code  VARCHAR(30) NOT NULL,
    version      INT         NOT NULL,
    name         VARCHAR(50) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    published_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_venue_layouts PRIMARY KEY (id),
    CONSTRAINT uk_venue_layouts_code_version UNIQUE (venue_id, layout_code, version),
    CONSTRAINT fk_venue_layouts_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT ck_venue_layouts_version CHECK (version > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- PK는 (layout_id, venue_seat_id). performance_seats가 이 복합 키를 참조한다.
CREATE TABLE layout_seats
(
    layout_id     BIGINT        NOT NULL,
    venue_seat_id BIGINT        NOT NULL,
    venue_id      BIGINT        NOT NULL,
    section_label VARCHAR(50)   NOT NULL,
    row_label     VARCHAR(10)   NOT NULL,
    seat_number   VARCHAR(10)   NOT NULL,
    pos_x         DECIMAL(10, 2) NOT NULL,
    pos_y         DECIMAL(10, 2) NOT NULL,
    is_sellable   BOOLEAN       NOT NULL,
    CONSTRAINT pk_layout_seats PRIMARY KEY (layout_id, venue_seat_id),
    CONSTRAINT fk_layout_seats_layout FOREIGN KEY (layout_id) REFERENCES venue_layouts (id),
    CONSTRAINT fk_layout_seats_venue_seat FOREIGN KEY (venue_seat_id) REFERENCES venue_seats (id),
    CONSTRAINT fk_layout_seats_venue FOREIGN KEY (venue_id) REFERENCES venues (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE concerts
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    venue_id   BIGINT       NOT NULL,
    seller_id  BIGINT       NOT NULL,
    title      VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT pk_concerts PRIMARY KEY (id),
    CONSTRAINT fk_concerts_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT fk_concerts_seller FOREIGN KEY (seller_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE performances
(
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    concert_id           BIGINT      NOT NULL,
    venue_id             BIGINT      NOT NULL,
    layout_id            BIGINT      NOT NULL,
    starts_at            DATETIME(6) NOT NULL,
    booking_opens_at     DATETIME(6) NOT NULL,
    booking_closes_at    DATETIME(6) NOT NULL,
    max_tickets_per_user INT         NOT NULL,
    status               VARCHAR(20) NOT NULL,
    CONSTRAINT pk_performances PRIMARY KEY (id),
    CONSTRAINT fk_performances_concert FOREIGN KEY (concert_id) REFERENCES concerts (id),
    CONSTRAINT fk_performances_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT fk_performances_layout FOREIGN KEY (layout_id) REFERENCES venue_layouts (id),
    CONSTRAINT ck_performances_booking_period CHECK (booking_opens_at < booking_closes_at),
    CONSTRAINT ck_performances_booking_before_start CHECK (booking_closes_at <= starts_at),
    CONSTRAINT ck_performances_max_tickets CHECK (max_tickets_per_user > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
