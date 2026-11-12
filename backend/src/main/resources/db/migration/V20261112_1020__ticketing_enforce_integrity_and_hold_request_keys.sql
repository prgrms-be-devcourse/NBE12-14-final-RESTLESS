-- 이번 티켓팅 ALTER를 통합한다.
-- 기존 dev 파일은 유지한다. 공통 결제 V20261112_1000 다음에 적용한다.
-- 1. 컬럼·부모 UNIQUE 준비  2. 복합 FK·상태 CHECK  3. 대체된 요청 키 인덱스 제거
-- 기존 중복·관계 불일치는 자동 수정하지 않으며 존재하면 적용이 실패한다.
-- 기존 요청 해시는 NULL로 두고 신규 요청부터 서비스가 반드시 기록한다.

ALTER TABLE tickets
    ADD CONSTRAINT uk_tickets_reservation_seat
        UNIQUE (reservation_id, performance_seat_id);

ALTER TABLE venue_sections
    ADD CONSTRAINT uk_venue_sections_id_venue UNIQUE (id, venue_id);

ALTER TABLE venue_seats
    ADD CONSTRAINT uk_venue_seats_id_venue UNIQUE (id, venue_id);

ALTER TABLE venue_layouts
    ADD CONSTRAINT uk_venue_layouts_id_venue UNIQUE (id, venue_id);

ALTER TABLE concerts
    ADD CONSTRAINT uk_concerts_id_venue UNIQUE (id, venue_id);

ALTER TABLE performances
    ADD CONSTRAINT uk_performances_id_layout UNIQUE (id, layout_id);

ALTER TABLE ticket_reservations
    ADD CONSTRAINT uk_ticket_reservations_id_performance UNIQUE (id, performance_id);

ALTER TABLE performance_seats
    ADD CONSTRAINT uk_performance_seats_id_performance UNIQUE (id, performance_id);

ALTER TABLE seat_holds
    ADD CONSTRAINT uk_seat_holds_seat_id UNIQUE (performance_seat_id, id);

ALTER TABLE ticket_reservations
    ADD COLUMN hold_request_hash BINARY(32) NULL,
    MODIFY COLUMN request_key VARCHAR(100)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT uk_ticket_reservations_user_request_key UNIQUE (user_id, request_key);

-- 복합 참조 및 상태 정합성

ALTER TABLE venue_seats
    ADD CONSTRAINT fk_venue_seats_section_venue
        FOREIGN KEY (section_id, venue_id) REFERENCES venue_sections (id, venue_id);

ALTER TABLE layout_seats
    ADD CONSTRAINT fk_layout_seats_layout_venue
        FOREIGN KEY (layout_id, venue_id) REFERENCES venue_layouts (id, venue_id);

ALTER TABLE layout_seats
    ADD CONSTRAINT fk_layout_seats_seat_venue
        FOREIGN KEY (venue_seat_id, venue_id) REFERENCES venue_seats (id, venue_id);

ALTER TABLE performances
    ADD CONSTRAINT fk_performances_concert_venue
        FOREIGN KEY (concert_id, venue_id) REFERENCES concerts (id, venue_id);

ALTER TABLE performances
    ADD CONSTRAINT fk_performances_layout_venue
        FOREIGN KEY (layout_id, venue_id) REFERENCES venue_layouts (id, venue_id);

ALTER TABLE performance_seats
    ADD CONSTRAINT fk_performance_seats_performance_layout
        FOREIGN KEY (performance_id, layout_id) REFERENCES performances (id, layout_id);

ALTER TABLE seat_holds
    ADD CONSTRAINT fk_seat_holds_reservation_performance
        FOREIGN KEY (reservation_id, performance_id)
            REFERENCES ticket_reservations (id, performance_id),
    ADD CONSTRAINT fk_seat_holds_seat_performance
        FOREIGN KEY (performance_seat_id, performance_id)
            REFERENCES performance_seats (id, performance_id);

ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_reservation_performance
        FOREIGN KEY (reservation_id, performance_id)
            REFERENCES ticket_reservations (id, performance_id),
    ADD CONSTRAINT fk_tickets_seat_performance
        FOREIGN KEY (performance_seat_id, performance_id)
            REFERENCES performance_seats (id, performance_id);

ALTER TABLE performance_seats
    ADD CONSTRAINT fk_performance_seats_current_hold_seat
        FOREIGN KEY (id, current_hold_id)
            REFERENCES seat_holds (performance_seat_id, id),
    ADD CONSTRAINT ck_performance_seats_status_owner CHECK (
        (status = 'AVAILABLE' AND current_hold_id IS NULL)
        OR (status IN ('HELD', 'SOLD') AND current_hold_id IS NOT NULL)
    );

-- 사용자별 키 UNIQUE 추가 후 기존 회차별 키 인덱스 제거

ALTER TABLE ticket_reservations
    DROP INDEX uk_ticket_reservations_request_key;
