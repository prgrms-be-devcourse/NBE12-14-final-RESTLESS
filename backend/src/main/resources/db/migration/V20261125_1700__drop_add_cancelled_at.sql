-- 드롭 삭제 시각 (#430). 삭제는 상태를 CANCELLED로 바꾸고 이 시각을 남긴다.
-- 결제 완료 건의 환불은 매일 00:30(KST) 배치가 처리한다. 기존 행은 NULL이다.
ALTER TABLE drops ADD COLUMN cancelled_at DATETIME(6) NULL AFTER created_at;
