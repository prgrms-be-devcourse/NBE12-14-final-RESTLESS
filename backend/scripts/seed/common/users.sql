-- 로컬 공통 계정. 모든 도메인 시드가 이 계정을 같이 쓴다.
-- ID 1~999는 공통 계정 구역이다.
--   일반 회원 n(1..100): ID n, user<nnn>@fanq.com, 닉네임 user-<nnn> (nnn은 001처럼 세 자리)
--   주최자 n(1..10):     ID 900 + n, organizer<nnn>@fanq.com, 닉네임 organizer-<nnn>
--   관리자:             ID 999, admin@fanq.com
-- 비밀번호는 모두 0000 (BCrypt). 앱에는 메일 발송 기능이 없어 이 주소로 메일이 나가지 않는다.
-- 계정을 늘리려면 아래 WHERE의 개수만 바꾼다.

SET @seed_now = UTC_TIMESTAMP(6);
SET @seed_password_hash = '$2y$10$y6OOQqM1Leen.h4r02zhS.toyPydGqZ6rOBGO1MybH6UaYBdTtMQC';

INSERT INTO users (id, email, password_hash, nickname, role, status, created_at, updated_at)
WITH RECURSIVE s (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 100)
SELECT n, CONCAT('user', LPAD(n, 3, '0'), '@fanq.com'), @seed_password_hash, CONCAT('user-', LPAD(n, 3, '0')), 'USER', 'ACTIVE',
       @seed_now, @seed_now
FROM s;

INSERT INTO users (id, email, password_hash, nickname, role, status, created_at, updated_at)
WITH RECURSIVE s (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s WHERE n < 10)
SELECT 900 + n, CONCAT('organizer', LPAD(n, 3, '0'), '@fanq.com'), @seed_password_hash, CONCAT('organizer-', LPAD(n, 3, '0')), 'ORGANIZER',
       'ACTIVE', @seed_now, @seed_now
FROM s;

INSERT INTO users (id, email, password_hash, nickname, role, status, created_at, updated_at)
VALUES (999, 'admin@fanq.com', @seed_password_hash, 'FANQ Administrator', 'ADMIN', 'ACTIVE', @seed_now, @seed_now);

-- 기본 배송지. 받는 분은 닉네임, 연락처는 회원 ID로 010-0000-0001 형식, 주소는 모두 판교역 광장이다.
UPDATE users
SET default_recipient = nickname,
    default_phone     = CONCAT('010-', LPAD(id DIV 10000, 4, '0'), '-', LPAD(id MOD 10000, 4, '0')),
    default_address   = '경기도 성남시 분당구 판교역로 160'
WHERE id < 1000;
