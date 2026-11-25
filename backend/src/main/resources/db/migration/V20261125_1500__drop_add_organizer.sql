-- 드롭 주최자 (#429). 주최자 API로 등록한 드롭은 공연 주최자(concerts.seller_id)를 담는다.
-- 관리자 API로 등록한 드롭과 기존 드롭은 NULL이고, 관리자만 관리한다.
-- 공연 주최자는 바뀌지 않으므로 등록 시점 값을 복사해 둔다. users로 물리 FK는 두지 않는다.
ALTER TABLE drops ADD COLUMN organizer_id BIGINT NULL AFTER concert_id;

CREATE INDEX idx_drops_organizer ON drops (organizer_id);
