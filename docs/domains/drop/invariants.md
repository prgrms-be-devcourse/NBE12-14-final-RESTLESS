# Drop 불변식

> 관련 코드: `backend/src/test/resources/harness/drop-invariants.sql`, `backend/src/main/java/com/restless/drop/domain/`
>
> 마지막 확인: 2026-11-24 (#388 만료 ADMITTED를 입장 허가 작업이 EXPIRED로 닫음. INV-16 계산은 그대로. #330 재고 버킷 분할. INV-01, INV-02는 버킷마다, SKU 수량은 버킷 합. INV-03에 같은 재고 버킷을 더했다(결정 D4). 하네스 SQL과 대조. 2026-11-20 #285 용어를 재고 점유로 통일. 내용 대조는 아래 2026-11-19 기준이다. #253 INV-16, INV-17 추가. 수용 테스트로 검증하며 INV-16, INV-17 SQL 검사는 하네스 후속 작업이다. 나머지는 2026-11-14 대조)

단위, 통합, 동시성, 장애 테스트의 기준이다. 테스트 이름이나 주석에 `INV-xx`를 붙여 추적한다.

| ID | 불변식 |
|---|---|
| INV-01 | 재고 보존: `total = available + reserved + sold`. `sold`는 환불되지 않은 순판매 수량이다. 재고 버킷마다 성립하고, SKU 수량은 버킷 합이다 ([ADR-0005](../../decisions/0005-drop-inventory-buckets.md)) |
| INV-02 | 모든 재고 수량은 0 이상이다. 재고 버킷마다 성립한다 |
| INV-03 | 예약, 확정, 반환, 환불 복원은 같은 SKU, 같은 재고 버킷을 대상으로 한다 (2026-11-22 결정 D4, #330) |
| INV-04 | 드롭 주문 하나에는 SKU 한 종류, 수량 1개만 있다 |
| INV-05 | 같은 사용자·드롭의 활성 구매 거래는 최대 1건이다 |
| INV-06 | 유효한 입장 허가가 없으면 신규 예약을 만들 수 없다 |
| INV-07 | 같은 구매 요청을 재시도해도 예약, 주문, 결제가 중복 생성되지 않는다 |
| INV-08 | PG 승인 요청 금액은 서버에 저장된 주문 스냅샷 금액과 같다 |
| INV-09 | PG 승인과 내부 구매 확정이 모두 성공한 경우에만 `PAID`다 |
| INV-10 | PG 승인 결과가 불명확한 거래를 임의로 성공이나 실패로 확정하지 않는다 |
| INV-11 | 예약을 이중 확정하거나 재고를 이중 반환하지 않는다 |
| INV-12 | 전액 환불이 확정되면 해당 SKU 재고를 정확히 한 번 복원한다 |
| INV-13 | 확정된 주문의 상품명, SKU, 금액, 배송지는 원본이 바뀌어도 변하지 않는다 |
| INV-14 | 판매 종료 후 새 예약을 만들지 않는다. 진행 중인 결제의 확인과 복구는 별도로 처리한다 |
| INV-15 | 예약이 기록한 현재 결제 시도(`current_payment_id`)가 아닌 결제 결과로 예약, 재고, 주문을 바꾸지 않는다 |
| INV-16 | 입장 제어를 쓸 때(`queue.required=true`) 커밋된 상태에서 SKU별 미사용 유효 `ADMITTED` 수는 그 SKU의 `available`을 넘지 않는다. 동시 입장 허가 작업이 있어도 마찬가지다 (2026-11-19, #254 구현) |
| INV-17 | SKU의 `available = 0`을 입장 허가 작업이 관찰하고 끝난 시점에는 그 SKU의 `WAITING`과 미사용 유효 `ADMITTED`가 남지 않는다. `reserved` 수량과 무관하다. 다른 SKU의 대기와, 이미 성공한 점유(`CONSUMED`와 그 재고 점유)는 바뀌지 않는다. 종료된 입장 허가로 신규 점유를 만들 수 없다 (2026-11-19, #254 구현) |

INV-17은 진입 직후부터 입장 허가 작업이 돌 때까지의 짧은 지연을 허용하는 형태라 SQL 검사로는 입장 허가 작업 실행 뒤에만 의미가 있다. INV-16, INV-17의 SQL 검사는 `drop_queue_entries.variant_id`가 생긴 뒤에 `backend/src/test/resources/harness/drop-invariants.sql`에 넣는다. #254가 컬럼을 추가했다. SQL 검사는 보호된 하네스 브랜치에서 후속 반영하고 그때까지는 `DropSkuQueuePolicyAcceptanceTest`가 같은 조건을 API로 검증한다.

INV-16, INV-17의 `available`은 SKU 버킷 `available` 합이다. 한 SELECT 문으로 읽으므로 READ_COMMITTED에서도 한 시점의 값이다 (#330).
