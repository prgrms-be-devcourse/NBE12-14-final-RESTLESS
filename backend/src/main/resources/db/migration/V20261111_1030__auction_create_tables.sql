-- 경매 (담당: 천종원)
-- performance_id, required_performance_id는 concerts(공연)를 가리킨다. 티켓팅 performances(회차)와 다르다.
-- 현재가 캐시는 두지 않는다. 낙찰자와 금액은 winning_bid_id로 계산한다.
-- 크레딧(credit_*) 테이블은 V20261111_1040에서 만든다.

CREATE TABLE auctions
(
    auction_id                 BIGINT       NOT NULL AUTO_INCREMENT,
    seller_user_id             BIGINT       NOT NULL,
    performance_id             BIGINT       NULL,
    title                      VARCHAR(200) NOT NULL,
    item_name                  VARCHAR(200) NOT NULL,
    item_description           TEXT         NOT NULL,
    starting_price_amount      BIGINT       NOT NULL,
    minimum_increment_amount   BIGINT       NOT NULL,
    access_policy              VARCHAR(30)  NOT NULL,
    required_performance_id    BIGINT       NULL,
    status                     VARCHAR(20)  NOT NULL,
    starts_at                  DATETIME(6)  NOT NULL,
    base_ends_at               DATETIME(6)  NOT NULL,
    ends_at                    DATETIME(6)  NOT NULL,
    extension_duration_seconds INT          NOT NULL,
    closed_at                  DATETIME(6)  NULL,
    revision                   BIGINT       NOT NULL DEFAULT 0,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    CONSTRAINT pk_auctions PRIMARY KEY (auction_id),
    CONSTRAINT fk_auctions_seller FOREIGN KEY (seller_user_id) REFERENCES users (id),
    CONSTRAINT fk_auctions_performance FOREIGN KEY (performance_id) REFERENCES concerts (id),
    CONSTRAINT fk_auctions_required_performance FOREIGN KEY (required_performance_id) REFERENCES concerts (id),
    CONSTRAINT ck_auctions_prices CHECK (starting_price_amount >= 0 AND minimum_increment_amount > 0),
    CONSTRAINT ck_auctions_period CHECK (starts_at < base_ends_at AND base_ends_at <= ends_at),
    CONSTRAINT ck_auctions_extension CHECK (extension_duration_seconds >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auction_bids
(
    auction_bid_id BIGINT       NOT NULL AUTO_INCREMENT,
    auction_id     BIGINT       NOT NULL,
    bidder_user_id BIGINT       NOT NULL,
    bid_amount     BIGINT       NOT NULL,
    bid_sequence   BIGINT       NOT NULL,
    request_key    VARCHAR(100) NOT NULL,
    accepted_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_auction_bids PRIMARY KEY (auction_bid_id),
    CONSTRAINT uk_auction_bids_request_key UNIQUE (request_key),
    CONSTRAINT uk_auction_bids_sequence UNIQUE (auction_id, bid_sequence),
    CONSTRAINT fk_auction_bids_auction FOREIGN KEY (auction_id) REFERENCES auctions (auction_id),
    CONSTRAINT fk_auction_bids_bidder FOREIGN KEY (bidder_user_id) REFERENCES users (id),
    CONSTRAINT ck_auction_bids_amount CHECK (bid_amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 경매당 결과는 최대 1건 (auction_id PK), 입찰 하나는 한 결과에만 쓰인다 (winning_bid_id UNIQUE).
CREATE TABLE auction_results
(
    auction_id     BIGINT      NOT NULL,
    winning_bid_id BIGINT      NOT NULL,
    finalized_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_auction_results PRIMARY KEY (auction_id),
    CONSTRAINT uk_auction_results_winning_bid UNIQUE (winning_bid_id),
    CONSTRAINT fk_auction_results_auction FOREIGN KEY (auction_id) REFERENCES auctions (auction_id),
    CONSTRAINT fk_auction_results_winning_bid FOREIGN KEY (winning_bid_id) REFERENCES auction_bids (auction_bid_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE auction_images
(
    auction_image_id BIGINT       NOT NULL AUTO_INCREMENT,
    auction_id       BIGINT       NOT NULL,
    image_url        VARCHAR(500) NOT NULL,
    display_order    INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT pk_auction_images PRIMARY KEY (auction_image_id),
    CONSTRAINT uk_auction_images_display_order UNIQUE (auction_id, display_order),
    CONSTRAINT fk_auction_images_auction FOREIGN KEY (auction_id) REFERENCES auctions (auction_id),
    CONSTRAINT ck_auction_images_display_order CHECK (display_order >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
