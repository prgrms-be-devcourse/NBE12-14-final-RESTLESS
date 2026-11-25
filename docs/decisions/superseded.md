# 폐기한 과거 가정

이전 초안에서 쓰던 가정이다. 더 이상 유효하지 않다.

| ID | 과거 가정 | 대체 |
|---|---|---|
| LEGACY-01 | `구매하기` 클릭 시 Redis 대기열 진입 | 배송·결제 페이지의 `결제하기` 클릭 시 MySQL 대기열 진입. 진입 시점은 다시 LEGACY-13으로 바뀌었다 |
| LEGACY-02 | 드롭당 단일 SKU | 드롭당 대표 상품 1개, SKU 1개 이상 |
| LEGACY-03 | 사용자당 총구매 1개 | 주문당 1개, 누적 재구매 허용, 동시 활성 거래만 제한 |
| LEGACY-04 | 처음부터 Redis를 대기열 영구 저장소로 사용 | MySQL Baseline 후 Redis 비교 |
| LEGACY-05 | PostgreSQL 전체 마이그레이션 확정 | 통제된 비교 실험 후 결정 |
| LEGACY-06 | 처음부터 Outbox + Kafka 필수 | Spring Event Listener로 시작 ([ADR-0001](0001-event-handling.md)) |
| LEGACY-07 | 트래픽 테스트 1, 2, 3차 고정 | 관측과 개선에 따른 반복 테스트 |
| LEGACY-08 | 다중 서버 배포 무조건 진행 | 단일 서버 한계 측정 후 결정 |
| LEGACY-09 | 실제 배송, 운송장 기능 포함 | 배송지 스냅샷까지만 제공 |
| LEGACY-10 | Kotlin 중심 | Java |
| LEGACY-11 | 쿠폰(선착순 발급) 도메인 | 팬미팅 추첨 도메인([fanmeeting](../domains/fanmeeting/README.md)). 쿠폰 코드와 설계 문서는 삭제했다(#156) |
| LEGACY-12 | SKU별 대기열에서 `available = 0`, `reserved > 0`이면 신규 입장만 중단하고 `WAITING`과 미사용 `ADMITTED`는 유지, 둘 다 0일 때만 종료 (2026-11-19 오전 안, #253) | `available = 0`이면 `reserved`와 무관하게 신규 진입과 입장 허가를 중단하고 기존 `WAITING`과 미사용 `ADMITTED`를 종료. 재고가 반환되면 새 진입으로 재개하고 종료된 입장 순번과 입장 허가는 복구하지 않는다 ([드롭 정책 7-1절](../domains/drop/policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)) |
| LEGACY-13 | 배송과 결제 페이지의 `결제하기` 클릭 시 입장 신청 | 상품 상세에서 배송지를 확인한 뒤 `구매하기` 클릭 시 입장 신청. 입장 허가 뒤 재고 점유는 화면이 자동으로 요청하고, 입장 신청부터 점유 결과까지 배송지 수정을 막는다 (2026-11-25, #444, [드롭 정책 4절](../domains/drop/policy.md#입장-신청-시점-decided)) |
