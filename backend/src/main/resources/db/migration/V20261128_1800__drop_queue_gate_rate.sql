-- 드롭 입장 속도 상한 (#551). 열 추가만 한다. restless.drop.queue.admit-rate-per-second가 0이면
-- (기본값, 상한 꺼짐) 두 열은 0으로 남고 읽지도 쓰지도 않는다.
-- rate_window_second: 이 SKU에서 마지막으로 입장 허가를 낸 초(epoch second). 시계가 크게 앞선 값만 무시하고 뒤로 돌리지 않는다.
-- rate_window_admits: 그 초에 모든 서버가 함께 낸 입장 허가 수.
-- 두 열은 게이트 행 락을 쥔 입장 허가 트랜잭션 안에서만 바뀌므로 모든 서버가 SKU마다 같은 창을 나눠 쓴다.
ALTER TABLE drop_queue_gates
    ADD COLUMN rate_window_second BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN rate_window_admits INT NOT NULL DEFAULT 0;
