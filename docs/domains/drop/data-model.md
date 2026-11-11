# Drop 데이터 모델

![Drop ERD](drop-erd.png)

아래는 논리 모델이다. 실제 DDL과 FK는 팀 공통 ID 체계와 주문 테이블이 확정된 뒤 작성한다.

## DROPS

대표 상품과 판매 기간

| 컬럼 | 의미 |
|---|---|
| id | PK |
| concert_id | 공연 FK. Notion 원본은 필수, NULL 허용 여부는 팀 확인 필요 |
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

제약: `UNIQUE(drop_id, sku_code)`, `CHECK(price_amount >= 0)`

- `is_active`가 false면 재고가 있어도 신규 예약할 수 없다.
- 판매 시작 후 가격, 옵션 변경은 제한한다.

## DROP_INVENTORIES

SKU별 재고

| 컬럼 | 의미 |
|---|---|
| variant_id | PK, FK |
| total_quantity | 총재고 |
| available_quantity | 신규 예약 가능 |
| reserved_quantity | 예약 중 |
| sold_quantity | 순판매 확정 |
| version | 낙관적 락용 (조건부 UPDATE만 쓰면 불필요) |

제약: 재고 음수 금지, 총재고 보존 (INV-01, INV-02)

## DROP_RESERVATIONS

재고 예약과 구매 결과 이력

| 컬럼 | 의미 |
|---|---|
| id | PK |
| drop_id | Drop FK |
| variant_id | SKU FK |
| user_id | Member FK |
| order_id | Order FK |
| request_key | 멱등성 키 |
| status | 예약 상태 |
| expires_at | 만료 시각 |
| created_at, updated_at | 생성, 변경 시각 |

제약: `UNIQUE(order_id)`, `UNIQUE(drop_id, user_id, request_key)`

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

## 구매 대기열 (OPEN)

별도 테이블(예: `drop_queue_entries`)이 필요할 수 있다. 최소 데이터:

- 드롭 ID, 사용자 ID
- 서버가 정하는 진입 순서
- 대기, 입장, 소비, 만료 상태
- 진입 시각, 입장 허가 시각, 구매권 만료 시각

PK, 인덱스, 구매권 저장 방식은 OPEN이다. 여러 Worker가 같은 사용자를 중복 입장시키면 안 된다. `SKIP LOCKED`를 쓴다면 FIFO 보장 여부를 따로 검증한다.

## 공통 테이블 (Drop 소유 아님)

USERS/Member Profile, CONCERTS, ORDERS, ORDER_ITEMS, ORDER_SHIPPING_SNAPSHOTS, PAYMENTS, PAYMENT_REFUNDS

Drop은 Order, Payment Repository를 직접 수정하지 않는다. 공통 Application Service 또는 Coordinator를 통해 조정한다.
