# SKU 대기열 배포 전환 (#254, PR #255)

이 릴리스는 **이미지 롤백만으로 되돌릴 수 없다**. 자동 배포는 dev merge 뒤 약 3분 안에 교체하며, 새 이미지가 180초 안에 health UP이 아니면 이전 이미지를 실행한다. migration 후 이전 코드는 `variant_id NOT NULL` 때문에 진입 INSERT가 실패하고 `UNAVAILABLE`을 읽지 못한다. 실패한 Flyway 기록은 이전 이미지의 기동도 막을 수 있다.

## 사전 점검

merge 전에 EC2에서 `sudo touch /opt/restless/deploy.paused`로 자동 배포를 멈춘다. 운영 RDS 버전을 `SELECT VERSION();`으로 확인한다. 테스트는 MySQL 8.4이며 CHECK 적용과 같은 ALTER 안의 DROP CHECK / CHECK 재추가는 MySQL 8.0.16 이상이 필요하다.

```sql
-- 결과가 있으면 constrain이 실패한다. 배포 전에 데이터를 수정한다.
SELECT DISTINCT q.drop_id FROM drop_queue_entries q
LEFT JOIN drop_variants v ON v.drop_id = q.drop_id WHERE v.id IS NULL;
-- 배포 시 닫히는 활성 대기와 테이블 크기
SELECT drop_id, status, COUNT(*) FROM drop_queue_entries
WHERE status IN ('WAITING','ADMITTED') GROUP BY drop_id, status;
SELECT COUNT(*) FROM drop_queue_entries;
```

FK와 NOT NULL ALTER가 테이블을 복사할 수 있어 큰 테이블은 점검 시간을 잡는다. 기존 단일 `V20261119_1800__drop_sku_queue.sql`을 이미 적용한 환경은 이번 분할 파일과 checksum이 다르다. 자동 repair로 덮지 말고 실제 schema와 이력을 확인해 별도 전환한다.

## 순서

1. `deploy.paused`가 존재하는지 확인한다. 프론트를 먼저 전환한다: 진입 요청 `variantId`, me의 404, `UNAVAILABLE`, 진입 409 처리. 이전 백엔드는 요청 본문을 무시하므로 이 프론트 변경을 먼저 배포할 수 있다.
2. 위 SQL을 점검하고 RDS 수동 snapshot을 만든다. 판매 중인 드롭이 없는 시간에 진행한다.
3. `deploy.paused`를 삭제해 배포를 재개한다. expand(1800), backfill(1801), constrain(1802)의 `flyway_schema_history.success = 1`과 health UP을 확인한다.
4. `SELECT COUNT(*) FROM drop_queue_gates;`와 `SELECT COUNT(*) FROM drop_variants;`가 같은지 확인한다. 판매 가능 SKU의 진입 POST에 `variantId`를 보내 201 응답과 SKU 응답을 확인한다.

배포 직후 기존 WAITING은 CLOSED여서 me가 404가 되고 다시 진입한다. 기존 미사용 ADMITTED는 점유 요청이 403이 된다. 이미 성공한 점유와 결제는 대기 종료로 바꾸지 않는다.

## 실패와 복구

- 자동 이미지 롤백을 신뢰하지 않는다. 실패하면 `deploy.paused`를 유지하거나 즉시 다시 만들고 forward fix 또는 snapshot 복구를 선택한다. snapshot 이후 데이터 유실과 외부 PG 정합성도 확인한다.
- 세 파일 분할은 재시도 단위를 줄일 뿐 **멱등성을 주지 않는다**. MySQL ADD COLUMN은 IF NOT EXISTS를 지원하지 않으며 DDL은 rollback되지 않는다.
- 옵션 없는 드롭의 이력 때문에 constrain이 실패하면 이력을 삭제하지 않는다. 적절한 SKU와 gate를 보완하고 남은 NULL `variant_id`를 직접 수정한다. backfill은 이미 성공한 단계이므로 repair가 다시 실행하지 않는다. 실제 schema를 대조한 뒤 Flyway repair와 실패 단계 재실행을 한다. 다른 DDL 실패도 부분 적용 상태부터 점검한다.

## 성능 실험 전

perf 이미지를 올리기 전에 seed의 gate 생성 또는 누락 gate 자동 복구 경로를 확인한다. k6는 `variantId`, 진입 409, `UNAVAILABLE`을 새 계약으로 처리해야 한다. 자동 복구가 있어도 seed와 k6 수정은 별도 후속 작업이다. 이 문서는 배포 승인이 아니다.
