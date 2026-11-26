-- 공연 단위 예매 오픈 시각과, 비공개로 돌려도 남는 공개 이력을 추가한다.
ALTER TABLE concerts
    ADD COLUMN booking_opens_at DATETIME(6) NULL,
    ADD COLUMN ever_published BOOLEAN NOT NULL DEFAULT TRUE;

-- 주최자 API로 만든 미공개 초안만 공개 이력이 없다. 기존 공연과 직접 INSERT는 공개 이력이 있다.
UPDATE concerts SET ever_published = (is_published OR published_at IS NOT NULL);

-- 회차들의 예매 오픈이 모두 같은 공연만 공연 값을 채운다. 서로 다르면 NULL로 두고 회차 값으로 판매한다.
UPDATE concerts c
    JOIN (SELECT concert_id, MIN(booking_opens_at) AS opens_at
          FROM performances
          GROUP BY concert_id
          HAVING MIN(booking_opens_at) = MAX(booking_opens_at)) p ON p.concert_id = c.id
SET c.booking_opens_at = p.opens_at;

-- 비공개 전환 뒤에도 최초 공개 시각을 남길 수 있도록 공개 여부 대신 공개 이력으로 묶는다.
ALTER TABLE concerts
    DROP CHECK ck_concerts_publication,
    ADD CONSTRAINT ck_concerts_publication_history CHECK (
        (NOT is_published OR ever_published) AND (published_at IS NULL OR ever_published)
    );
