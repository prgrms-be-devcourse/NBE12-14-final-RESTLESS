# Drop 데이터 모델

> 관련 코드: `backend/src/main/resources/db/migration/V*__drop_*.sql`, `backend/src/main/java/com/restless/drop/domain/`
>
> 마지막 확인: 2026-11-25 (#429 주최자 컬럼 V20261125_1500과 대조. 이전: 2026-11-24 (#377 입장 순번 인덱스 V20261124_1700과 대조. 2026-11-22 #330 재고 버킷 분할 migration V20261122_1400, 1401과 대조. 2026-11-20 #285 용어를 재고 점유로 통일. 2026-11-19 #254 SKU별 입장 제어 migration과 대조. 나머지는 2026-11-14 #92 구현과 대조)

![Drop ERD](drop-erd.svg)

그림은 `drop-erd.py`가 그린다. 드롭 migration이 컬럼을 바꾸면 그 파일의 표를 고치고 `python3 docs/domains/drop/drop-erd.py`를 실행한다.

아래는 논리 모델이다. 실제 DDL은 `backend/src/main/resources/db/migration/`의 `V*__drop_*.sql`이 정본이다.

## DROPS

대표 상품과 판매 기간

| 컬럼 | 의미 |
|---|---|
| id | PK |
| concert_id | 공연 FK. 현재 구현은 필수(NOT NULL FK, 등록 API도 필수). NULL 허용으로 바꿀지는 팀 확인 ([열린 결정](../../product/open-questions.md)) |
| organizer_id | 드롭 주최자 회원 ID (#429). 주최자 API로 등록하면 공연 주최자(`concerts.seller_id`)를 복사한다. 관리자 API로 등록한 드롭과 기존 드롭은 NULL이고 관리자만 관리한다. users로 물리 FK는 두지 않는다. 인덱스 `idx_drops_organizer` |
| title | 상품명 |
| description | 설명 |
| image_url | 대표 이미지 |
| status | 판매 상태 |
| opens_at | 판매 시작 |
| closes_at | 판매 종료 |
| created_at | 등록 시각 |

제약: `CHECK (opens_at < closes_at)`

## DROP_VARIANTS

SKU, 옵션

| 컬럼 | 의미 |
|---|---|
| id | PK |
| drop_id | Drop FK |
| sku_code | SKU 코드 |
| option_name | FREE, S, M, L 등 |
| price_amount | 원 단위 정수 가격 |
| is_active | 옵션 활성 여부 |

제약: `UNIQUE(drop_id, sku_code)`, `CHECK(price_amount >= 0)`. 등록 API는 1원 이상만 받는다(#92). DB 제약은 이미 있는 행 때문에 그대로 둔다

- `is_active`가 false면 재고가 있어도 신규 예약할 수 없다.
- 판매 시작 후 가격, 옵션 변경은 제한한다.

## DROP_INVENTORIES

SKU 머리 행. 재고 수량은 `DROP_INVENTORY_BUCKETS`에 있고 SKU 수량은 버킷 합이다 ([ADR-0005](../../decisions/0005-drop-inventory-buckets.md), #330).

| 컬럼 | 의미 |
|---|---|
| variant_id | PK, FK |
| total_quantity | 총재고. 버킷 `total_quantity` 합과 같다 |
| bucket_count | 재고 버킷 수 K. 1~16. 생성 때 `min(설정 K, 총재고)`로 정하고 바꾸지 않는다 |
| available_quantity, reserved_quantity, sold_quantity | 폐기 예정. 생성 때 값(기존 SKU는 #330 이관 시점 값)으로 멈춰 있다. 읽지 않는다 |
| version | 폐기 예정. 갱신하지 않는다 |

제약: `ck_drop_inventories_bucket_count`(1~16). 수량 컬럼의 NOT NULL과 보존 CHECK는 지울 때까지 남는다. 축소 migration은 성능 측정으로 채택이 확정된 뒤 별도 PR이다 (결정 D1)

TX1과 정산은 이 행을 갱신하지 않는다. TX1은 `bucket_count`만 잠그지 않고 읽는다.

## DROP_INVENTORY_BUCKETS

SKU 재고 버킷. SKU 수량은 버킷 합이다

| 컬럼 | 의미 |
|---|---|
| variant_id | PK, FK (`drop_inventories`) |
| bucket_no | PK. 0부터 `bucket_count - 1`까지 |
| total_quantity | 버킷 총재고 |
| available_quantity | 신규 점유 가능 |
| reserved_quantity | 점유 중 |
| sold_quantity | 순판매 확정 |
| version | 갱신마다 +1. 조건부 UPDATE로 판정하므로 낙관적 락으로 쓰지 않는다 |

제약: 버킷마다 재고 음수 금지와 보존 CHECK (INV-01, INV-02). PK `(variant_id, bucket_no)`

- 생성 분배: 버킷 i의 수량은 `floor(N / K) + (i < N mod K ? 1 : 0)`이다. 나머지는 앞 버킷부터 1개씩 더한다
- 이관(V20261122_1401): 기존 SKU는 점유와 판매를 모두 버킷 0에 두고, 남은 `available`만 `min(4, available)`개(최소 1)로 나눴다
- 모든 재고 변경은 한 버킷 안에서 두 수량을 맞바꾼다. 버킷 사이 재분배는 하지 않는다

## DROP_RESERVATIONS

재고 점유와 구매 결과 이력

| 컬럼 | 의미 |
|---|---|
| id | PK |
| drop_id | Drop FK |
| variant_id | SKU FK |
| user_id | Member FK |
| order_id | Order FK |
| request_key | 멱등성 키. `utf8mb4_0900_bin`이라 대소문자·악센트를 구분한다 (V20261113_1701) |
| status | 예약 상태 |
| expires_at | 만료 시각 |
| current_payment_id | 승인 시작을 받아들인 현재 결제 시도 (`payments.id`, Nullable, 물리 FK 없음). V20261113_1700에서 추가 |
| bucket_no | 이 점유가 재고를 가져간 버킷 (기본 0, V20261122_1400). TX1의 재고 UPDATE가 같은 문장에서 기록한다. 확정, 반환, 환불 복원은 이 버킷만 바꾼다 (INV-03). 버킷으로 가는 물리 FK는 없다. FK 검사의 공유 락이 같은 버킷 UPDATE의 배타 락으로 올라가며 교착이 나기 때문이다. 하네스 SQL이 대신 검사한다 |
| created_at, updated_at | 생성, 변경 시각 |

제약: `UNIQUE(order_id)`, `UNIQUE(drop_id, user_id, request_key)`. 만료 작업용 인덱스 `ix_drop_reservations_expiry (status, expires_at)`

`current_payment_id`는 재결제 때 새 시도로 바뀐다. 결제 결과의 `paymentId`와 비교해 다른 시도의 결과를 거른다 (INV-15).

같은 멱등성 키로 다른 요청 내용이 오는 경우를 잡으려면 요청 해시 컬럼을 추가할 수 있다.

## DROP_PURCHASE_SLOTS

사용자별 동시 활성 구매 제한 (INV-05)

| 컬럼 | 의미 |
|---|---|
| drop_id | 복합 PK |
| user_id | 복합 PK |
| active_reservation_id | 진행 중 예약 (Nullable) |
| updated_at | 갱신 시각 |

슬롯은 DB 트랜잭션에서 원자적으로 점유, 해제한다.

## DROP_QUEUE_ENTRIES (DECIDED, D4)

> SKU별 입장 제어는 `V20261119_1800__drop_sku_queue_expand.sql`, `V20261119_1801__drop_sku_queue_backfill.sql`, `V20261119_1802__drop_sku_queue_constrain.sql`로 구현했다(#254). 기존 활성 대기는 CLOSED로 닫고, 소비된 이력은 연결된 점유 SKU를 우선 사용한다. 나머지 이력은 해당 드롭의 첫 SKU를 역사적 FK로 지정하며 기존 입장 순번이나 입장 허가는 복구하지 않는다. SKU가 없는 비정상 드롭은 NOT NULL 검증에서 실패하므로 migration 전에 점검한다.

- `id`: 서버가 발급하는 증가 PK. 대략적인 진입 순서에도 사용한다.
- `drop_id`, `user_id`: 드롭·회원 FK.
- `variant_id`: 입장 신청 때 고른 SKU(`drop_variants.id`) FK. NOT NULL. 입장 순번, 입장 수 계산, 입장 허가 귀속의 기준이다.
- `status`: WAITING / ADMITTED / CONSUMED / EXPIRED / CLOSED / UNAVAILABLE. 현재 구매 불가로 종료한 대기를 위한 상태 `UNAVAILABLE`을 추가한다(이름 결정 2026-11-19, [policy.md 7-1절](policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)). CHECK 제약 `ck_drop_queue_status`도 이 값을 허용한다. 종료는 행 삭제가 아니라 이 컬럼의 변경이다.
- `entered_at`, `last_seen_at`: 진입 시각과 마지막으로 기록한 WAITING heartbeat 시각. 진입 시 두 값은 같다.
- `admitted_at`, `admitted_until`, `consumed_at`: 입장·입장 허가 만료·소비 시각.
- `active_user_id`: WAITING 또는 ADMITTED일 때만 user_id인 생성 컬럼.
- `UNIQUE(drop_id, active_user_id)`: 회원과 드롭당 살아 있는 대기(`WAITING`, `ADMITTED`)는 최대 1건. 종료된 행은 이 키에서 빠지므로 재고가 반환된 뒤 새 행으로 다시 진입할 수 있다. SKU별이어도 INV-05(드롭당 활성 구매 1건)에 맞춰 유지하는 안이다. SKU를 바꿔 다시 들어오면 기존 행을 `CLOSED`로 바꾼 뒤(키에서 빠진다) 새 행을 만든다. 같은 트랜잭션에서 먼저 flush해야 키가 겹치지 않는다.
- SKU별 입장은 `(variant_id, status, id)` 인덱스와 `FOR UPDATE SKIP LOCKED`로 고른다. `(variant_id, status, admitted_until)`로 유효 ADMITTED를 센다. 종료 대상은 같은 SKU의 살아 있는 행을 `FOR UPDATE`로 읽어 건너뛰지 않는다.
- 입장 가능 수 계산 `max(0, available - 미사용 유효 ADMITTED 수)`는 SKU 버킷 `available_quantity` 합과 이 표의 `ADMITTED` 행을 함께 읽는다. 두 값을 읽고 입장 허가로 전이하는 사이에 다른 입장 허가 작업이 끼어들지 못해야 한다 (INV-16, [transactions.md](transactions.md) D4).
- `WAITING`의 순서는 `id`(애플리케이션 입장 순번)이며 DB 락 획득 순서가 아니다. 재진입은 새 `id`를 받으므로 항상 뒤에 선다.
- 회원별 최신 상태는 `(drop_id, user_id, id)`, 전체 대기 Gauge는 `(status, last_seen_at)` 인덱스를 사용한다.
- 입장 순번 COUNT는 `(variant_id, status, id, last_seen_at)` 인덱스 `ix_drop_queue_sku_position`만 읽는다. 쿼리에 `USE INDEX`로 이 인덱스를 지정한다. 인덱스 이름을 바꾸면 쿼리도 같이 고친다 (#377).
- 만료, 소비, 종료 이력은 조회할 수 있으며, 재진입하면 새 행을 추가한다. 종료된 입장 순번과 입장 허가는 복구하지 않는다. CLOSED 이력만 있으면 상태 조회는 404다. 최신 행이 `UNAVAILABLE`이면 404가 아니라 그 상태를 돌려준다.

## DROP_QUEUE_GATES

- `variant_id`: PK이자 `drop_variants.id` FK. SKU마다 한 행.
- 입장과 종료 batch만 `FOR UPDATE SKIP LOCKED`로 락 획득한다. 게이트가 사용 중이면 즉시 건너뛰고 다음 주기에 재시도한다.
- gate row lock은 transaction 종료 시 자동 해제된다. TX1과 PG 호출은 이 게이트를 사용하지 않는다.
- 기존 SKU는 expand migration에서, 새 SKU는 운영자 생성 transaction에서 게이트를 만든다. 작업이 누락 gate를 발견하면 로그와 `drop.queue.gate.missing` 합계를 남기고 별도 짧은 transaction의 INSERT IGNORE로 보완한 뒤 한 번 재시도한다.

## 공통 테이블 (Drop 소유 아님)

USERS/Member Profile, CONCERTS, ORDERS, ORDER_ITEMS, ORDER_SHIPPING_SNAPSHOTS, PAYMENTS, PAYMENT_REFUNDS

Drop은 Order, Payment Repository를 직접 수정하지 않는다. 공통 Application Service 또는 Coordinator를 통해 조정한다.
