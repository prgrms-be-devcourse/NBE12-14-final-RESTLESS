-- 경매·크레딧 테이블 제약과 인덱스 보강 (담당: 천종원)
-- 머지된 V20261111_1030, V20261111_1040은 고치지 않고 ALTER로 바꾼다. 테이블은 비어 있다.
-- 기준: docs/domains/auction/invariants.md "스키마 보강이 필요한 항목", policy.md 4절

-- 경매: 상태 값, 연장 시간 30~300초, 시작·종료 스케줄러 조회 인덱스
ALTER TABLE auctions
    DROP CHECK ck_auctions_extension,
    ADD CONSTRAINT ck_auctions_extension_range CHECK (extension_duration_seconds BETWEEN 30 AND 300),
    ADD CONSTRAINT ck_auctions_status CHECK (status IN ('SCHEDULED', 'OPEN', 'CLOSED')),
    ADD INDEX idx_auctions_status_starts_at (status, starts_at),
    ADD INDEX idx_auctions_status_ends_at (status, ends_at);

-- 입찰: 중복 요청 키는 회원별로 유일하다 (INV-AU-12)
ALTER TABLE auction_bids
    ADD CONSTRAINT uk_auction_bids_bidder_request_key UNIQUE (bidder_user_id, request_key),
    DROP INDEX uk_auction_bids_request_key;

-- 결과: 유찰(UNSOLD)도 결과 행으로 남긴다. 낙찰(SOLD)이면 낙찰 입찰이 있고, UNSOLD면 없다 (INV-AU-02)
ALTER TABLE auction_results
    MODIFY COLUMN winning_bid_id BIGINT NULL,
    ADD COLUMN result_type VARCHAR(20) NOT NULL AFTER auction_id,
    ADD CONSTRAINT ck_auction_results_result_type CHECK (
        (result_type = 'SOLD' AND winning_bid_id IS NOT NULL)
        OR (result_type = 'UNSOLD' AND winning_bid_id IS NULL));

-- 지갑: 사용 가능 크레딧(balance - reserved)은 음수가 될 수 없다 (INV-AU-06)
ALTER TABLE credit_wallets
    ADD CONSTRAINT ck_credit_wallets_reserved_within_balance CHECK (reserved_amount <= balance_amount);

-- 확보: 상태 값과 상태별 시각 짝
ALTER TABLE credit_holds
    ADD CONSTRAINT ck_credit_holds_status CHECK (
        (status = 'HELD' AND released_at IS NULL AND captured_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND captured_at IS NULL)
        OR (status = 'CAPTURED' AND captured_at IS NOT NULL AND released_at IS NULL));

-- 내역: 변동 종류
ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_type CHECK (transaction_type IN ('GRANT', 'HOLD', 'RELEASE', 'CAPTURE'));
