# 폐기한 과거 가정

이전 초안에서 쓰던 가정이다. 더 이상 유효하지 않다.

| ID | 과거 가정 | 대체 |
|---|---|---|
| LEGACY-01 | `구매하기` 클릭 시 Redis 대기열 진입 | 배송·결제 페이지의 `결제하기` 클릭 시 MySQL 대기열 진입 |
| LEGACY-02 | 드롭당 단일 SKU | 드롭당 대표 상품 1개, SKU 1개 이상 |
| LEGACY-03 | 사용자당 총구매 1개 | 주문당 1개, 누적 재구매 허용, 동시 활성 거래만 제한 |
| LEGACY-04 | 처음부터 Redis를 대기열 영구 저장소로 사용 | MySQL Baseline 후 Redis 비교 |
| LEGACY-05 | PostgreSQL 전체 마이그레이션 확정 | 통제된 비교 실험 후 결정 |
| LEGACY-06 | 처음부터 Outbox + Kafka 필수 | Spring Event Listener로 시작 ([ADR-0001](0001-event-handling.md)) |
| LEGACY-07 | 트래픽 테스트 1, 2, 3차 고정 | 관측과 개선에 따른 반복 테스트 |
| LEGACY-08 | 다중 서버 배포 무조건 진행 | 단일 서버 한계 측정 후 결정 |
| LEGACY-09 | 실제 배송, 운송장 기능 포함 | 배송지 스냅샷까지만 제공 |
| LEGACY-10 | Kotlin 중심 | Java |
