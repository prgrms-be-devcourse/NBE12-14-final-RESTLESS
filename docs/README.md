# 문서 지도

처음 보는 색인이다. 필요한 문서만 골라 읽는다.

## 정본 위치

| 폴더 | 내용 |
|---|---|
| [product/](product/) | [기획서](product/proposal.md), [개요](product/overview.md), [일정](product/roadmap.md), [열린 결정 사항](product/open-questions.md) |
| [domains/](domains/) | 도메인별 정책, 불변식, 상태 머신, 트랜잭션, 데이터 모델 |
| [architecture/](architecture/) | [기술 결정 현황, 패키지 규칙, 배포](architecture/overview.md), [통합 ERD](architecture/erd.md) |
| [contracts/](contracts/) | [API 계약 목록과 공통 규칙](contracts/api/README.md), 이벤트 계약([events/](contracts/events/)) |
| [decisions/](decisions/) | [ADR-0001 이벤트 처리](decisions/0001-event-handling.md), [ADR-0002 공통 규약](decisions/0002-common-conventions.md), [ADR-0003 결제 모듈 경계](decisions/0003-payment-module-boundary.md), [ADR-0004 요청 횟수 제한](decisions/0004-request-rate-limit.md), [ADR-0005 드롭 재고 행 분할](decisions/0005-drop-inventory-buckets.md), [ADR-0006 드롭 대기실 Redis 전환](decisions/0006-drop-waiting-room-redis.md) (실험 승인), [ADR-0007 경매 실시간 전달 서버 여러 대](decisions/0007-auction-stream-cross-server.md), [ADR-0008 좌석 조회 응답 0.5초 공유](decisions/0008-ticketing-seat-response-cache.md), [ADR-0009 좌석 상태 응답 공유](decisions/0009-ticketing-seat-status-response-cache.md), [폐기한 가정](decisions/superseded.md) |
| [testing/](testing/) | [테스트 전략과 실행 명령](testing/strategy.md), [성능 실험 계획](testing/performance.md) |
| [operations/](operations/) | [장애 처리 설계](operations/failure-handling.md), [SKU 입장 제어 배포 전환](operations/drop-sku-queue-cutover.md), [드롭 재고 버킷 분할 배포 전환](operations/drop-inventory-buckets-cutover.md) |

## 작업별로 읽을 문서

| 작업 | 문서 |
|---|---|
| 공연·티켓팅 테이블/컬럼 확인 | [데이터 사전](domains/ticketing/data-dictionary.md), [티켓팅 문서 색인](domains/ticketing/README.md), [통합 ERD](architecture/erd.md) |
| 도메인 기능 구현 | domains/<도메인>/ 전체, contracts/api/<도메인>.md, architecture/overview.md 2절, architecture/erd.md |
| 재고, 결제, 환불 로직 변경 | domains/drop/invariants.md, transactions.md, state-machines.md, operations/failure-handling.md, decisions/0003-payment-module-boundary.md, contracts/api/payment.md |
| 요청 횟수 제한 변경 | [ADR-0004](decisions/0004-request-rate-limit.md), [API 공통 규칙의 요청 횟수 제한](contracts/api/README.md#요청-횟수-제한) |
| 이벤트, 알림 작업 | decisions/0001-event-handling.md, contracts/events/drop.md |
| 경매 등록, 입찰, 종료, 크레딧 | domains/auction/ 전체 (design.md는 원본), contracts/api/auction.md |
| 팬미팅 응모, 추첨, 결제, 승격 | domains/fanmeeting/ 전체, contracts/api/fanmeeting.md, contracts/events/fanmeeting.md |
| 테스트 작성 | testing/strategy.md, 해당 도메인 invariants.md (팬미팅은 test-scenarios.md도) |
| AI 구현 검증·PR 승인 준비 | [독립 검증 절차와 필수 스킬](testing/independent-verification.md) |
| 성능 개선 | testing/performance.md, [드롭 계획과 병목 기록](testing/drop-performance-plan.md), [실행](testing/drop-performance-procedure.md) (7절: ALB 멀티 인스턴스, DB 프로브, JFR), [EC2 도구](../perf/ec2/README.md), [측정 규약](testing/drop-performance-measurement.md), 관련 ADR |
| 서비스 의도, 사용자 시나리오, 범위, 역할, 완료기준 | [product/proposal.md](product/proposal.md) |
| 정책이 애매할 때 | product/open-questions.md (목록에 있으면 임의로 정하지 않는다) |

## 상태 표기

모든 문서가 이 표를 쓴다. 2026-11-20에 계약 문서의 정의(2026-11-16)를 기준으로 하나로 맞췄다.

| 표기 | 의미 |
|---|---|
| DRAFT | 설계 중. 바뀔 수 있다 |
| PROPOSED | 제안. 팀 합의나 검증이 필요하다 |
| OPEN | 미확정. 구현에 영향이 있으면 확인한다 |
| EXPERIMENTAL | 비교 실험 뒤 채택을 정한다 |
| DECIDED | 합의했지만 아직 구현하지 않았다 |
| IMPLEMENTED | 구현했다. 문서와 코드가 다르면 코드가 현재 동작이다. 다른 점을 찾으면 담당자에게 알리고 문서를 고친다 |
| DEFERRED | 현재 범위에서 뺐다 |

정책 문서의 `DECIDED (날짜)`는 그날 결정했다는 기록이다. 구현 여부는 문서 머리의 마지막 확인이나 구현 표기를 본다.
