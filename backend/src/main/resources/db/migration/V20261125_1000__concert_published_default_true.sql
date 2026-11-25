-- 공연 행을 직접 INSERT하는 경로(타 도메인 fixture 등)는 기본 공개로 둔다.
-- 주최자 API는 엔티티가 is_published = FALSE를 명시해 비공개 초안으로 생성한다.
ALTER TABLE concerts
    ALTER COLUMN is_published SET DEFAULT TRUE;
