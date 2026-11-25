-- 공연 상세(소개·기간·소요 시간), 공개 표식, 공통 배치와 공통 구역 가격을 추가한다.
ALTER TABLE concerts
    ADD COLUMN description VARCHAR(5000) NULL,
    ADD COLUMN period_start_date DATE NULL,
    ADD COLUMN period_end_date DATE NULL,
    ADD COLUMN duration_minutes INT NULL,
    ADD CONSTRAINT ck_concerts_period CHECK (
        (period_start_date IS NULL AND period_end_date IS NULL)
        OR (period_start_date IS NOT NULL AND period_end_date IS NOT NULL AND period_start_date <= period_end_date)
    ),
    ADD CONSTRAINT ck_concerts_duration CHECK (duration_minutes IS NULL OR duration_minutes > 0);

-- 기존 공연은 공개 유지. 실제 과거 공개 시각은 추정하지 않는다.
ALTER TABLE concerts
    ADD COLUMN is_published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN published_at DATETIME(6) NULL,
    ADD COLUMN layout_id BIGINT NULL,
    ADD CONSTRAINT fk_concerts_layout FOREIGN KEY (layout_id) REFERENCES venue_layouts(id),
    ADD CONSTRAINT ck_concerts_publication CHECK (is_published OR published_at IS NULL);

UPDATE concerts SET is_published = TRUE;

-- 기존 회차의 배치·가격은 별도 점검 전 자동 이관하지 않는다.
CREATE TABLE concert_section_prices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    concert_id BIGINT NOT NULL,
    section_label VARCHAR(50) NOT NULL,
    grade VARCHAR(20) NOT NULL,
    price BIGINT NOT NULL,
    CONSTRAINT pk_concert_section_prices PRIMARY KEY (id),
    CONSTRAINT uk_concert_section_prices_section UNIQUE (concert_id, section_label),
    CONSTRAINT fk_concert_section_prices_concert FOREIGN KEY (concert_id) REFERENCES concerts(id),
    CONSTRAINT ck_concert_section_prices_price CHECK (price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
