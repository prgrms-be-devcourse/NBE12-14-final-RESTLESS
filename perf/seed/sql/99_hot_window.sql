-- 핫 이벤트 판매 기간과 Refresh Token 만료를 실행 시각 기준으로 다시 맞춘다.
-- 시드 마지막에 실행하고, 스냅샷을 복원한 뒤 실험 전에도 seed.sh --refresh-window로 다시 실행한다.
-- 재고, 점유, 주문은 건드리지 않는다.
SET @now = UTC_TIMESTAMP(6);

UPDATE drops
SET opens_at  = @now - INTERVAL 1 MINUTE,
    closes_at = @now + INTERVAL @hot_window_hours HOUR
WHERE id = 1;

UPDATE refresh_tokens
SET expires_at = @now + INTERVAL @token_ttl_hours HOUR
WHERE revoked_at IS NULL;
