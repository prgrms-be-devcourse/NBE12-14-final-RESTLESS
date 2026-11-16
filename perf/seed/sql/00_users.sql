-- 회원, Refresh Token. 모든 도메인이 같은 계정을 공유한다.
-- seed.sh가 세션 변수(@users, @t0, @salt 등)를 먼저 정하고 이 파일부터 차례로 실행한다.
-- ID는 명시적으로 넣는다: 일반 회원 1..@users, 관리자 @users+1, 주최자 @users+2.

-- 숫자 테이블. 모든 대량 INSERT ... SELECT의 행 생성원이다. 95_cleanup.sql이 지운다.
DROP TABLE IF EXISTS _perf_seq;
CREATE TABLE _perf_seq (n INT NOT NULL PRIMARY KEY) ENGINE = InnoDB;
SET SESSION cte_max_recursion_depth = @seq_size + 1;
INSERT INTO _perf_seq (n)
WITH RECURSIVE s (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM s WHERE n + 1 < @seq_size)
SELECT n FROM s;

-- 시드 기록. 운영 DB에는 이 테이블이 없으므로 --refresh, --verify의 안전장치로도 쓴다.
CREATE TABLE IF NOT EXISTS perf_seed_meta
(
    k VARCHAR(64)  NOT NULL PRIMARY KEY,
    v VARCHAR(255) NOT NULL
) ENGINE = InnoDB;

INSERT INTO perf_seed_meta (k, v) VALUES
    ('scale', @scale),
    ('salt', @salt),
    ('seeded_at', DATE_FORMAT(@t0, '%Y-%m-%dT%H:%i:%s.%fZ')),
    ('users', @users),
    ('contention_ratio', @ratio),
    ('drop_hot_id', 1),
    ('drop_hot_stock', @hot_stock),
    ('drop_hot_participants', @hot_stock * @ratio),
    ('drop_bg_drops', @bg_drops),
    ('drop_bg_reservations', @bg_reservations);

-- 비밀번호는 모두 perf-password (BCrypt). README 참고.
INSERT INTO users (id, email, password_hash, nickname, role, status, created_at, updated_at)
SELECT s.n + 1,
       CONCAT('perf-user-', s.n + 1, '@perf.local'),
       @password_hash,
       CONCAT('perf', s.n + 1),
       'USER',
       'ACTIVE',
       @t0 - INTERVAL 30 DAY + INTERVAL s.n SECOND,
       @t0 - INTERVAL 30 DAY + INTERVAL s.n SECOND
FROM _perf_seq s
WHERE s.n < @users;

INSERT INTO users (id, email, password_hash, nickname, role, status, created_at, updated_at) VALUES
    (@users + 1, 'perf-admin@perf.local', @password_hash, 'perf-admin', 'ADMIN', 'ACTIVE',
     @t0 - INTERVAL 31 DAY, @t0 - INTERVAL 31 DAY),
    (@users + 2, 'perf-organizer@perf.local', @password_hash, 'perf-organizer', 'ORGANIZER', 'ACTIVE',
     @t0 - INTERVAL 31 DAY, @t0 - INTERVAL 31 DAY);

-- 원문은 perf-rt-<salt>-<id>. lib/tokens.py가 같은 규칙으로 CSV에 쓴다.
INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, expires_at, created_at)
SELECT u.id,
       u.id,
       SHA2(CONCAT('perf-rt-', @salt, '-', u.id), 256),
       CONCAT('00000000-0000-4000-8000-', LPAD(LOWER(HEX(u.id)), 12, '0')),
       @t0 + INTERVAL @token_ttl_hours HOUR,
       @t0
FROM users u;

