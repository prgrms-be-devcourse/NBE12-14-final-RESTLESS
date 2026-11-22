# 드롭 재고 버킷 분할 배포 전환 (#330, ADR-0005)

> 대상: `V20261122_1400__drop_inventory_buckets_expand.sql`, `V20261122_1401__drop_inventory_buckets_backfill.sql`과 같은 이미지. 이 배포 한 번에만 쓴다. 이 문서는 배포 승인이 아니다.

이 릴리스는 **이전 이미지로 되돌리면 안 된다**. 이관 뒤 이전 코드는 `drop_inventories`의 수량만 바꾸고 버킷은 그대로 둔다. 그 상태로 다음 배포가 버킷을 읽으면 이미 팔린 재고를 다시 판다.
배포 스크립트(`deploy/ec2/restless-deploy.sh`)는 이전 컨테이너를 지운 뒤 새 컨테이너를 띄운다. 그래서 두 버전이 동시에 돌지는 않는다. 위험은 새 앱이 180초 안에 health UP이 아니면 스크립트가 이전 이미지를 자동으로 실행하는 것이다. 그래서 이번만 자동 배포를 멈추고 직접 배포한다 (결정 D5).

판매 시간 조건은 두지 않는다. 두 버전이 겹치지 않으므로 판매 중이어도 이관은 맞다. 배포 중 몇 초는 앱이 응답하지 않는다.

## 사전 점검

1. merge 전에 EC2에서 `sudo touch /opt/restless/deploy.paused`로 자동 배포를 멈춘다. 부하 테스트 컨테이너가 돌고 있지 않은지 본다.
2. 운영 RDS 버전을 `SELECT VERSION();`으로 확인한다. `drop_reservations`의 ADD COLUMN이 INSTANT이려면 MySQL 8.0.29 이상이어야 한다. 테스트는 MySQL 8.4다.
3. 이관 대상과 결과를 미리 본다.

```sql
-- 이관 뒤 SKU마다 생길 버킷 수
SELECT variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity,
       LEAST(4, GREATEST(available_quantity, 1)) AS bucket_count
FROM drop_inventories;
-- 지금 보존식이 깨진 SKU가 있으면 이관 결과도 깨진다. 0이어야 한다
SELECT COUNT(*) FROM drop_inventories
WHERE total_quantity <> available_quantity + reserved_quantity + sold_quantity;
```

4. RDS 수동 snapshot을 만든다.

## 순서

1. `deploy.paused`가 있는지 확인하고 PR을 dev에 merge한다. GHCR에 새 dev 이미지가 올라올 때까지 기다린다.
2. EC2에서 직접 교체한다. 스크립트와 같은 인자로 띄우되 실패해도 이전 이미지를 실행하지 않는다.

```bash
IMAGE=ghcr.io/prgrms-be-devcourse/nbe12-14-final-restless-backend:dev
docker pull -q "$IMAGE"
docker rm -f restless
docker run -d --name restless --restart unless-stopped \
  --env-file /opt/restless/prod.env -p 8080:8080 "$IMAGE"
curl -fsS http://localhost:8080/actuator/health   # UP이 될 때까지 다시 확인
```

3. `flyway_schema_history`에서 1400과 1401의 `success = 1`을 확인한다.
4. 아래 배포 뒤 검사 SQL을 돌린다. 모두 0이어야 한다.
5. 판매 중 SKU의 상세 조회 `soldOut`이 이관 전 `available = 0` 여부와 같은지 본다.
6. 문제가 없으면 `deploy.paused`를 지워 자동 배포를 재개한다. 이후 배포의 이전 이미지는 이 이미지라 자동 되돌리기가 다시 안전하다.

## 배포 뒤 검사 SQL

하네스 `backend/src/test/resources/harness/drop-invariants.sql`의 재고 검사와 같다.

```sql
-- 버킷 보존식과 음수 금지 (INV-01, INV-02)
SELECT COUNT(*) FROM drop_inventory_buckets
WHERE total_quantity <> available_quantity + reserved_quantity + sold_quantity
   OR available_quantity < 0 OR reserved_quantity < 0 OR sold_quantity < 0;
-- SKU 총재고 = 버킷 총재고 합, 버킷 수 = bucket_count
SELECT COUNT(*) FROM drop_inventories i
WHERE i.total_quantity <> (SELECT COALESCE(SUM(b.total_quantity), 0) FROM drop_inventory_buckets b WHERE b.variant_id = i.variant_id)
   OR i.bucket_count <> (SELECT COUNT(*) FROM drop_inventory_buckets b WHERE b.variant_id = i.variant_id);
-- 버킷 reserved, sold = 그 버킷을 기록한 점유 수 (INV-01, INV-03)
SELECT COUNT(*) FROM drop_inventory_buckets b
WHERE b.reserved_quantity <> (SELECT COUNT(*) FROM drop_reservations r WHERE r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no
                                AND r.status IN ('RESERVED', 'CONFIRMING', 'RECONCILING', 'COMPENSATING'))
   OR b.sold_quantity <> (SELECT COUNT(*) FROM drop_reservations r WHERE r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no
                            AND r.status = 'CONFIRMED');
-- 점유가 가리키는 버킷이 있다
SELECT COUNT(*) FROM drop_reservations r
LEFT JOIN drop_inventory_buckets b ON b.variant_id = r.variant_id AND b.bucket_no = r.bucket_no
WHERE b.variant_id IS NULL;
```

세 번째 검사는 이관 전에도 `drop_inventories`의 reserved, sold가 점유 수와 맞았을 때만 0이다. 이관 전 같은 검사를 `drop_inventories`로 돌려 0인지 먼저 확인해 두면 원인을 나눌 수 있다.

## 실패와 복구

- 이전 이미지로 되돌리지 않는다. 실패하면 `deploy.paused`를 유지하고 고쳐서 다시 배포한다 (결정 D5).
- expand(1400)는 DDL이라 rollback되지 않는다. backfill(1401)은 DML 한 트랜잭션이라 실패하면 데이터가 남지 않는다. expand만 성공한 상태면 Flyway가 1401을 실패로 기록한다. 실제 schema와 이력을 대조한 뒤 원인을 고치고 `flyway repair`로 실패 기록을 지운 다음 다시 띄운다.
- 배포 뒤 검사가 0이 아니면 판매를 멈추고 원인을 찾는다. 이관 전 snapshot과 비교한다. snapshot 복구는 그 뒤 데이터 유실과 PG 정합성을 함께 확인한 뒤에만 한다.
- 수량 컬럼 삭제(축소 migration)는 이 배포에 없다. 성능 측정으로 채택이 확정된 뒤 별도 PR로 한다 (결정 D1).
