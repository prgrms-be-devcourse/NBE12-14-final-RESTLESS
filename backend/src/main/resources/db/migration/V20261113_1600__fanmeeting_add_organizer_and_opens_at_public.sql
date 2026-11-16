-- 팬미팅 행사에 주최자와 오픈 시각 공개 여부 추가 (담당: 홍승연)
-- V20261111_1020__fanmeeting_create_tables.sql은 머지돼서 고치지 않는다.
-- organizer_id: 행사를 만든 회원 ID. users로 물리 FK는 두지 않는다(모듈 경계, 로그인 회원 ID로만 채운다).
-- opens_at_public: true면 예고 오픈(OPEN 전에도 오픈 시각 공개), false면 게릴라 오픈(OPEN 전에는 숨김).

ALTER TABLE fan_meeting_events
    ADD COLUMN organizer_id    BIGINT  NOT NULL AFTER event_id,
    ADD COLUMN opens_at_public BOOLEAN NOT NULL AFTER entry_closes_at;
