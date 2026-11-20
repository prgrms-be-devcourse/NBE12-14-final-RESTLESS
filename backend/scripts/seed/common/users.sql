-- 로컬 공통 계정. 모든 도메인 시드가 이 계정을 같이 쓴다.
-- ID 1~999는 공통 계정 구역이다. 비밀번호는 모두 fanq1234 (BCrypt).
-- 앱에는 메일 발송 기능이 없어 이 주소로 메일이 나가지 않는다.

SET @seed_now = UTC_TIMESTAMP(6);
SET @seed_password_hash = '$2y$10$vNv/O2ax3qJVU4T7wdnQkuVA9dqcewr2IEfmj3pWSVTi2SGXnmS3y';

INSERT INTO users (id, email, password_hash, nickname, role, status,
                   default_recipient, default_phone, default_address, created_at, updated_at)
VALUES (1, 'admin@fanq.local', @seed_password_hash, '관리자', 'ADMIN', 'ACTIVE',
        NULL, NULL, NULL, @seed_now, @seed_now),
       (2, 'organizer@fanq.local', @seed_password_hash, '주최자', 'ORGANIZER', 'ACTIVE',
        NULL, NULL, NULL, @seed_now, @seed_now),
       (11, 'user1@fanq.local', @seed_password_hash, '팬1', 'USER', 'ACTIVE',
        '팬1', '010-0000-0011', '서울특별시 송파구 올림픽로 424', @seed_now, @seed_now),
       (12, 'user2@fanq.local', @seed_password_hash, '팬2', 'USER', 'ACTIVE',
        '팬2', '010-0000-0012', '서울특별시 송파구 올림픽로 424', @seed_now, @seed_now),
       (13, 'user3@fanq.local', @seed_password_hash, '팬3', 'USER', 'ACTIVE',
        '팬3', '010-0000-0013', '서울특별시 송파구 올림픽로 424', @seed_now, @seed_now),
       (14, 'user4@fanq.local', @seed_password_hash, '팬4', 'USER', 'ACTIVE',
        '팬4', '010-0000-0014', '서울특별시 송파구 올림픽로 424', @seed_now, @seed_now),
       (15, 'user5@fanq.local', @seed_password_hash, '팬5', 'USER', 'ACTIVE',
        '팬5', '010-0000-0015', '서울특별시 송파구 올림픽로 424', @seed_now, @seed_now);
