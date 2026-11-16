-- 공연장, 공연. drops.concert_id FK 때문에 드롭보다 먼저 만든다.
-- 드롭에 필요한 최소 행만 넣는다. 좌석, 회차 같은 티켓팅 데이터는 넣지 않는다.
-- 공연장은 실제 공연장의 이름과 공개 주소다. 아티스트와 투어는 실제 상표와 겹치지 않게 지어낸 이름이다.
-- 공연 1 = 핫 드롭용, 공연 2.. = 종료된 드롭 10개마다 1개.
-- _perf_artists, _perf_venues는 20_drop.sql도 쓰고 95_cleanup.sql이 지운다.

DROP TABLE IF EXISTS _perf_venues;
CREATE TABLE _perf_venues (i INT PRIMARY KEY, name VARCHAR(100), address VARCHAR(255), city VARCHAR(20));
INSERT INTO _perf_venues VALUES
    (0, 'KSPO DOME', '서울특별시 송파구 올림픽로 424', 'SEOUL'),
    (1, '고척스카이돔', '서울특별시 구로구 경인로 430', 'SEOUL'),
    (2, '잠실실내체육관', '서울특별시 송파구 올림픽로 25', 'SEOUL'),
    (3, '인스파이어 아레나', '인천광역시 중구 공항문화로 127', 'INCHEON'),
    (4, '장충체육관', '서울특별시 중구 동호로 241', 'SEOUL'),
    (5, '블루스퀘어', '서울특별시 용산구 이태원로 294', 'SEOUL'),
    (6, '벡스코', '부산광역시 해운대구 APEC로 55', 'BUSAN'),
    (7, '킨텍스', '경기도 고양시 일산서구 킨텍스로 217-60', 'GOYANG');

DROP TABLE IF EXISTS _perf_artists;
CREATE TABLE _perf_artists (i INT PRIMARY KEY, name VARCHAR(50), tour VARCHAR(50));
INSERT INTO _perf_artists VALUES
    (0, 'NOVA:LINE', 'AFTERGLOW'),
    (1, 'HALOECHO', 'BLUE HOUR'),
    (2, 'MOONVEIL', 'MIDNIGHT BLOOM'),
    (3, 'AURA7', 'STARDUST'),
    (4, 'LUMIRE', 'DAYBREAK'),
    (5, 'VELVET ORBIT', 'ECLIPSE'),
    (6, 'PRISM9', 'SIGNAL'),
    (7, 'SOLARIS:K', 'WILDFLOWER'),
    (8, 'NEON PETAL', 'PRISMATIC'),
    (9, 'IRIS BLOOM', 'HORIZON');

INSERT INTO venues (id, name, address, timezone, status, created_at)
SELECT v.i + 1, v.name, v.address, 'Asia/Seoul', 'ACTIVE', @t0 - INTERVAL 400 DAY
FROM _perf_venues v;

-- 공연 c(0..): 아티스트 c MOD 10, 공연장 c MOD 8, 판매자 주최자 c MOD @organizers.
-- 열 번째마다 아티스트가 한 바퀴 돌고, 앞선 회차일수록 한 해씩 이전 투어로 붙인다.
INSERT INTO concerts (id, venue_id, seller_id, title, status, created_at)
SELECT s.n + 1,
       MOD(s.n, 8) + 1,
       @users + 2 + MOD(s.n, @organizers),
       CONCAT(a.name, ' ', 2026 - s.n DIV 10, ' TOUR <', a.tour, '> IN ', v.city),
       'SCHEDULED',
       @t0 - INTERVAL 60 DAY
FROM _perf_seq s
JOIN _perf_artists a ON a.i = MOD(s.n, 10)
JOIN _perf_venues v ON v.i = MOD(s.n, 8)
WHERE s.n < 1 + CEIL(@bg_drops / 10);
