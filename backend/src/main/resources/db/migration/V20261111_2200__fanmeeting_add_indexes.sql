-- 팬미팅 조회용 인덱스 (담당: 홍승연)
-- V20261111_1020__fanmeeting_create_tables.sql에 빠진 인덱스를 추가한다. 머지된 파일은 고치지 않는다.
-- Worker 조회와 승격·만료의 FOR UPDATE가 인덱스 없이 돌면 훑은 행을 전부 잠그므로 구현 전에 넣는다.
-- 기준: docs/domains/fanmeeting/data-model.md 2절

-- 응모 열기, 응모 마감 Worker: 시각이 된 행사 찾기
ALTER TABLE fan_meeting_events
    ADD INDEX idx_fan_meeting_events_status_opens (status, entry_opens_at),
    ADD INDEX idx_fan_meeting_events_status_closes (status, entry_closes_at);

-- 추첨: 이 행사의 유효한 응모를 응모 순서대로
ALTER TABLE fan_meeting_applications
    ADD INDEX idx_fan_meeting_applications_event_status (event_id, status, applied_at);

-- 추첨 Worker: 멈춘 추첨 작업(임대 만료) 다시 잡기
ALTER TABLE fan_meeting_draw_runs
    ADD INDEX idx_fan_meeting_draw_runs_status_lease (status, lease_until);

-- 승격: 다음 예비 순번 / 정원 확인 / 내 당첨 내역
ALTER TABLE fan_meeting_selections
    ADD INDEX idx_fan_meeting_selections_event_type_status_rank (event_id, selection_type, status, selection_rank),
    ADD INDEX idx_fan_meeting_selections_event_status (event_id, status),
    ADD INDEX idx_fan_meeting_selections_user_status (user_id, status);

-- 정원 확인 / 만료 Worker: 기한 지난 결제 대기 주문 / 내 주문 내역
ALTER TABLE fan_meeting_orders
    ADD INDEX idx_fan_meeting_orders_event_status (event_id, status),
    ADD INDEX idx_fan_meeting_orders_status_issued (status, issued_at),
    ADD INDEX idx_fan_meeting_orders_user_status (user_id, status);
