-- 로컬 한정판 드롭 시드 (담당: 이태호)
-- seed.sh가 DB를 비우고 common/users.sql 다음에 실행한다. 단독으로 실행하지 않는다.
--
-- ID 규칙
--   공유 테이블(venues, concerts)은 드롭 구역 2001부터 쓴다.
--   드롭 테이블(drops, drop_variants)은 1부터 쓴다.
--   판매 기간은 실행 시각 기준이다. 다시 실행하면 판매 중 드롭의 기간이 지금 기준으로 다시 맞춰진다.
--
-- 드롭 1 판매 중. SKU 3개 중 실버 한정은 재고 1개라 한 번 사면 품절을 볼 수 있다.
-- 드롭 2 판매 예정. 1일 뒤 열린다.
-- 드롭 3 판매 종료.
-- 점유, 주문, 결제는 넣지 않는다. 화면에서 직접 구매해 만든다.
-- 이미지 주소는 없는 주소라 프론트가 그래픽 포스터로 대신 그린다.

SET @seed_now = UTC_TIMESTAMP(6);

-- drops.concert_id가 공연을 FK로 참조해서 공연장과 공연을 하나씩 둔다. 좌석과 회차는 넣지 않는다.
INSERT INTO venues (id, name, address, timezone, status, created_at)
VALUES (2001, 'KSPO DOME', '서울특별시 송파구 올림픽로 424', 'Asia/Seoul', 'ACTIVE', @seed_now);

INSERT INTO concerts (id, venue_id, seller_id, title, status, created_at)
VALUES (2001, 2001, 901, 'NOVA:LINE 2026 TOUR <AFTERGLOW> IN SEOUL', 'SCHEDULED', @seed_now);

INSERT INTO drops (id, concert_id, title, description, image_url, opens_at, closes_at, status, created_at)
VALUES (1, 2001, 'NOVA:LINE <AFTERGLOW> 공식 응원봉 한정판',
        'NOVA:LINE 2026 투어 기념 한정 수량 응원봉. 1인 1개 구매.',
        'https://cdn.fanq.local/drops/1.png',
        @seed_now - INTERVAL 1 HOUR, @seed_now + INTERVAL 7 DAY, 'OPEN', @seed_now - INTERVAL 3 DAY),
       (2, 2001, 'NOVA:LINE <AFTERGLOW> 포토카드 세트',
        'NOVA:LINE 2026 투어 포토카드 세트. 판매 예정.',
        'https://cdn.fanq.local/drops/2.png',
        @seed_now + INTERVAL 1 DAY, @seed_now + INTERVAL 8 DAY, 'SCHEDULED', @seed_now - INTERVAL 1 DAY),
       (3, 2001, 'NOVA:LINE <AFTERGLOW> 투어 후드 티셔츠',
        'NOVA:LINE 2026 투어 후드 티셔츠. 판매 종료.',
        'https://cdn.fanq.local/drops/3.png',
        @seed_now - INTERVAL 10 DAY, @seed_now - INTERVAL 3 DAY, 'CLOSED', @seed_now - INTERVAL 14 DAY);

INSERT INTO drop_variants (id, drop_id, sku_code, option_name, price_amount, is_active)
VALUES (1, 1, 'LIGHTSTICK-BLACK', '블랙', 49000, TRUE),
       (2, 1, 'LIGHTSTICK-WHITE', '화이트', 49000, TRUE),
       (3, 1, 'LIGHTSTICK-SILVER', '실버 한정', 59000, TRUE),
       (4, 2, 'PHOTOCARD-A', 'A ver.', 15000, TRUE),
       (5, 2, 'PHOTOCARD-B', 'B ver.', 15000, TRUE),
       (6, 3, 'HOODIE-M', 'M', 69000, TRUE),
       (7, 3, 'HOODIE-L', 'L', 69000, TRUE);

-- 재고는 버킷에 나뉜다 (ADR-0005). 드롭 등록 API와 같이 SKU마다 min(4, 총재고)개로 나누고 나머지는 앞 버킷부터 1개씩.
-- drop_inventories의 수량 컬럼은 생성 때 값으로 멈춘다. 읽는 값은 total_quantity와 bucket_count뿐이다.
INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, bucket_count)
VALUES (1, 30, 30, 0, 0, 4),
       (2, 20, 20, 0, 0, 4),
       (3, 1, 1, 0, 0, 1),
       (4, 50, 50, 0, 0, 4),
       (5, 50, 50, 0, 0, 4),
       (6, 10, 10, 0, 0, 4),
       (7, 10, 10, 0, 0, 4);

INSERT INTO drop_inventory_buckets
    (variant_id, bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity)
WITH RECURSIVE seq (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < 15)
SELECT i.variant_id, s.n,
       FLOOR(i.total_quantity / i.bucket_count) + (s.n < MOD(i.total_quantity, i.bucket_count)),
       FLOOR(i.total_quantity / i.bucket_count) + (s.n < MOD(i.total_quantity, i.bucket_count)),
       0, 0
FROM drop_inventories i
JOIN seq s ON s.n < i.bucket_count
WHERE i.variant_id BETWEEN 1 AND 7;

-- SKU마다 입장 허가 게이트가 하나 있어야 한다. 드롭 등록 API도 SKU를 만들 때 함께 만든다.
INSERT INTO drop_queue_gates (variant_id)
SELECT id FROM drop_variants WHERE drop_id IN (1, 2, 3);
