# ADR-0002. 공통 규약: DB, ID, API 경로

- 상태: Accepted
- 날짜: 2026-11-10

## 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| DB | 모든 도메인 MySQL 8로 시작 | 모듈러 모놀리스 하나에 DB 하나. PostgreSQL은 비교 실험 후 판단 (EXP-05) |
| PK | `BIGINT AUTO_INCREMENT` | InnoDB 클러스터드 인덱스에 순차 삽입되어 단순하고 빠르다 |
| 시각 | `DATETIME(6)`, UTC 저장 | MySQL에는 TIMESTAMPTZ가 없다 |
| API 경로 | `/api/...` (버전 없음) | 클라이언트가 우리 프론트 하나이고 함께 배포한다. 외부 공개 API가 생기면 그때 버전을 붙인다 |

## 영향

- 티켓팅, 쿠폰 원본 문서의 PostgreSQL 전용 문법은 MySQL 대안으로 바꾼다 (티켓팅은 [좌석 소유권 설계](../domains/ticketing/seat-ownership.md) 참고).
- 쿠폰의 UUID PK, 드롭과 쿠폰의 `/api/v1` 경로는 이 규약으로 바꾼다.
