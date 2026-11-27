-- 선점 만료 후보를 기한·ID 순서로 정렬 없이 앞에서부터 읽는다.
CREATE INDEX idx_ticket_reservations_hold_expiry
    ON ticket_reservations (status, hold_expires_at, id);
