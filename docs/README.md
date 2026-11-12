# 문서 지도

처음 보는 색인이다. 필요한 문서만 골라 읽는다.

## 정본 위치

| 폴더 | 내용 |
|---|---|
| [product/](product/) | [개요](product/overview.md), [일정](product/roadmap.md), [열린 결정 사항](product/open-questions.md) |
| [domains/](domains/) | 도메인별 정책, 불변식, 상태 머신, 트랜잭션, 데이터 모델 |
| [architecture/](architecture/) | [기술 결정 현황, 패키지 규칙, 배포](architecture/overview.md), [통합 ERD](architecture/erd.md) |
| [contracts/](contracts/) | [API 계약 목록과 공통 규칙](contracts/api/README.md), 이벤트 계약([events/](contracts/events/)) |
| [decisions/](decisions/) | [ADR-0001 이벤트 처리](decisions/0001-event-handling.md), [ADR-0002 공통 규약](decisions/0002-common-conventions.md), [ADR-0003 결제 모듈 경계](decisions/0003-payment-module-boundary.md), [폐기한 가정](decisions/superseded.md) |
| [testing/](testing/) | [테스트 전략과 실행 명령](testing/strategy.md), [성능 실험 계획](testing/performance.md) |
| [operations/](operations/) | [장애 처리 설계](operations/failure-handling.md) |
| [reports/](reports/) | 성능 실험 결과 (측정 조건 포함) |

## 작업별로 읽을 문서

| 작업 | 문서 |
|---|---|
| 공연·티켓팅 테이블/컬럼 확인 | [데이터 사전](domains/ticketing/data-dictionary.md), [티켓팅 문서 색인](domains/ticketing/README.md), [통합 ERD](architecture/erd.md) |
| 도메인 기능 구현 | domains/<도메인>/ 전체, contracts/api/<도메인>.md, architecture/overview.md 2절, architecture/erd.md |
| 재고, 결제, 환불 로직 변경 | domains/drop/invariants.md, transactions.md, state-machines.md, operations/failure-handling.md, decisions/0003-payment-module-boundary.md |
| 이벤트, 알림 작업 | decisions/0001-event-handling.md, contracts/events/drop.md |
| 팬미팅 응모, 추첨, 결제, 승격 | domains/fanmeeting/ 전체, contracts/api/fanmeeting.md, contracts/events/fanmeeting.md |
| 테스트 작성 | testing/strategy.md, 해당 도메인 invariants.md (팬미팅은 test-scenarios.md도) |
| 성능 실험 | testing/performance.md, 관련 ADR |
| 정책이 애매할 때 | product/open-questions.md (목록에 있으면 임의로 정하지 않는다) |

## 상태 표기

| 표기 | 의미 |
|---|---|
| DECIDED | 결정됨 |
| PROPOSED | 제안, 팀 합의나 검증 필요 |
| EXPERIMENTAL | 비교 실험 후 채택 결정 |
| DEFERRED | 현재 범위 제외 |
| OPEN | 미확정, 구현에 영향이 있으면 확인 |
