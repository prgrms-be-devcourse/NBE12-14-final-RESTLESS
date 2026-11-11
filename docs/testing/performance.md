# 성능 실험 계획

## 1. 연구 주제

고동시성 한정판 드롭의 거래 정합성 보장과 Admission Control 기반 Throughput, Latency 최적화

핵심 질문: 한정된 재고에 구매 요청이 순간적으로 몰릴 때, 초과 판매와 중복 결제를 막으면서 높은 지속 처리량과 낮은 꼬리 지연을 어떻게 확보하는가?

## 2. 목표

| 우선순위 | 목표 | 지표 |
|---|---|---|
| P0 | Consistency | 초과 예약, 초과 판매, 중복 주문, 중복 차감·반환, 불변식 위반 모두 0건 |
| P0 | Throughput | 성공 예약 TPS, 구매 확정 TPS, 대기열 등록·조회 RPS, 최대 지속 처리량 |
| P0 | Latency | 재고 예약, 대기열 API p50/p95/p99, 대기 체류 p95/p99, 전체 구매 소요 p95/p99 |
| P0 | Admission Control | 입장 허가율 제어, DB 커넥션 풀과 Lock Wait 보호, 5xx·타임아웃 억제, 정상 품절·대기 안내와 서버 오류 구분 |
| 확장 | Scalability | 서버 증설 전후 처리량, 지연 비교 |
| 확장 | Resilience | 장애 후 거래 복구 |
| 확장 | Efficiency | 같은 자원에서 처리 가능한 거래량, 비용 |

최종 기준: 정합성 위반 0건과 미리 정한 지연, 오류율 목표를 지키면서 달성 가능한 최대 지속 처리량을 높인다.

목표 수치는 측정 전이므로 TBD다. 측정하지 않은 수치를 쓰지 않는다.

## 3. 반복 방법

Observe → Hypothesize → Change → Verify Correctness → Benchmark → Compare → Decide → Document

- 한 비교 실험에서 주요 독립변인은 하나만 바꾼다.
- 개선되지 않았으면 그 이유도 결과로 남긴다.
- 부하 생성기의 dropped iterations, CPU, 메모리 한계도 확인한다.
- 결과는 [reports/](../reports/)에 측정 조건과 함께 남긴다.

## 4. 실험 목록

| ID | 주제 | 비교 | 측정 |
|---|---|---|---|
| EXP-01 | 재고 동시성 제어 | SELECT→UPDATE, 비관적 락, 조건부 UPDATE | 초과 예약, 성공 TPS, p95/p99, Lock Wait |
| EXP-02 | Admission Control 효과 | 대기열 없음, MySQL 대기열 | 예약 TPS, 오류율, p95/p99, 대기시간, DB 사용량 |
| EXP-03 | 대기열 저장소 | MySQL, Redis | 같은 API, 정책, 허가율, 유입 패턴, 재고, 서버 사양에서 비교 |
| EXP-04 | SQL, 트랜잭션 개선 | DB 왕복 수, 조건부 UPDATE, 트랜잭션 범위, 인덱스, Hot Row | 지연, 처리량 |
| EXP-05 | DBMS (EXPERIMENTAL) | MySQL, PostgreSQL | 같은 스키마, 인덱스, 트랜잭션 조건 |
| EXP-06 | 수평 확장 (EXPERIMENTAL) | 단일, 다중 서버 | 공유 DB 병목 분석 포함 |
| EXP-07 | 이벤트 유실 ([ADR-0001](../decisions/0001-event-handling.md)) | Spring Event, (조건 충족 시) Outbox | 강제 종료 시 누락 건수, 구매 확정 p95 |
