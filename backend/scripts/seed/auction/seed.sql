-- 로컬 경매 시드 (담당: 천종원)
-- seed.sh가 DB를 비우고 common/users.sql, ticketing, drop 다음에 실행한다. 단독으로 실행하지 않는다.
--
-- ID 규칙
--   경매 테이블(auctions, auction_results)은 1부터 쓴다. 공유 테이블에는 행을 만들지 않는다.
--   판매자는 공통 주최자 901~903이다. 입찰은 공통 회원 user001~100으로 화면에서 한다.
--   시각은 실행 시각 기준이다. 다시 실행하면 진행 중·시작 예정 경매의 기간이 지금 기준으로 다시 맞춰진다.
--
-- 경매 1 진행 중. 1일 뒤 마감. 입찰·추월·실시간 가격 변경 확인용
-- 경매 2 진행 중. 10분 뒤 마감. 마감 직전 연장과 종료·낙찰 확인용 (입찰이 없으면 유찰로 끝난다)
-- 경매 3 진행 중. 3일 뒤 마감. 시작가·최소 인상액이 큰 경매
-- 경매 4 시작 예정. 5분 뒤 자동 시작. 스케줄러의 시작 처리 확인용
-- 경매 5 시작 예정. 1일 뒤 시작. 판매자(901)의 시작 전 수정·삭제 확인용
-- 경매 6 종료, 유찰
--
-- 입찰, 크레딧 확보, 낙찰은 넣지 않는다. 크레딧 지갑·내역과 맞춰야 해서 화면에서 직접 만든다.
-- 유찰 경매는 입찰이 없으므로 크레딧과 무관하게 결과 행만 둔다.
-- 이미지는 1차 범위 밖이라 auction_images에 넣지 않는다 (policy.md).
-- revision: 시작 전 0, 시작하면 1, 종료하면 1 더 오른다.

SET @seed_now = UTC_TIMESTAMP(6);

INSERT INTO auctions (auction_id, seller_user_id, title, item_name, item_description,
                      starting_price_amount, minimum_increment_amount, access_policy, status,
                      starts_at, base_ends_at, ends_at, extension_duration_seconds, closed_at, revision,
                      created_at, updated_at)
VALUES (1, 901, 'NOVA:LINE <AFTERGLOW> 서울 공연 친필 사인 포스터', '친필 사인 포스터',
        'NOVA:LINE 2026 투어 서울 공연 멤버 전원 친필 사인 포스터 1장.',
        50000, 1000, 'ALL', 'OPEN',
        @seed_now - INTERVAL 1 HOUR, @seed_now + INTERVAL 1 DAY, @seed_now + INTERVAL 1 DAY, 60, NULL, 1,
        @seed_now - INTERVAL 1 DAY, @seed_now - INTERVAL 1 HOUR),
       (2, 901, 'NOVA:LINE <AFTERGLOW> 리허설 착용 응원 타월', '리허설 착용 응원 타월',
        '서울 공연 리허설에서 사용한 응원 타월. 마감 직전 입찰하면 종료 시각이 연장된다.',
        30000, 1000, 'ALL', 'OPEN',
        @seed_now - INTERVAL 50 MINUTE, @seed_now + INTERVAL 10 MINUTE, @seed_now + INTERVAL 10 MINUTE, 60, NULL, 1,
        @seed_now - INTERVAL 1 DAY, @seed_now - INTERVAL 50 MINUTE),
       (3, 902, 'NOVA:LINE 데뷔 앨범 친필 사인 초판', '데뷔 앨범 친필 사인 초판',
        '데뷔 앨범 초판 한정반에 멤버 전원 친필 사인. 최소 인상액 10,000 크레딧.',
        300000, 10000, 'ALL', 'OPEN',
        @seed_now - INTERVAL 2 HOUR, @seed_now + INTERVAL 3 DAY, @seed_now + INTERVAL 3 DAY, 120, NULL, 1,
        @seed_now - INTERVAL 2 DAY, @seed_now - INTERVAL 2 HOUR),
       (4, 902, 'NOVA:LINE 팬미팅 무대 의상 스카프', '무대 의상 스카프',
        '팬미팅 무대에서 착용한 스카프. 5분 뒤 경매가 자동으로 시작된다.',
        100000, 5000, 'ALL', 'SCHEDULED',
        @seed_now + INTERVAL 5 MINUTE, @seed_now + INTERVAL 1 DAY, @seed_now + INTERVAL 1 DAY, 60, NULL, 0,
        @seed_now - INTERVAL 1 HOUR, @seed_now - INTERVAL 1 HOUR),
       (5, 901, 'NOVA:LINE 투어 백스테이지 폴라로이드', '백스테이지 폴라로이드',
        '투어 백스테이지에서 찍은 폴라로이드 사진 1장. 시작 전이라 판매자가 수정·삭제할 수 있다.',
        20000, 1000, 'ALL', 'SCHEDULED',
        @seed_now + INTERVAL 1 DAY, @seed_now + INTERVAL 2 DAY, @seed_now + INTERVAL 2 DAY, 60, NULL, 0,
        @seed_now - INTERVAL 1 HOUR, @seed_now - INTERVAL 1 HOUR),
       (6, 903, 'NOVA:LINE 시즌 그리팅 미공개 포토카드', '미공개 포토카드',
        '시즌 그리팅 미공개 포토카드. 입찰 없이 종료돼 유찰됐다.',
        40000, 1000, 'ALL', 'CLOSED',
        @seed_now - INTERVAL 3 DAY, @seed_now - INTERVAL 1 DAY, @seed_now - INTERVAL 1 DAY, 60, @seed_now - INTERVAL 1 DAY, 2,
        @seed_now - INTERVAL 4 DAY, @seed_now - INTERVAL 1 DAY);

INSERT INTO auction_results (auction_id, result_type, winning_bid_id, finalized_at)
VALUES (6, 'UNSOLD', NULL, @seed_now - INTERVAL 1 DAY);
