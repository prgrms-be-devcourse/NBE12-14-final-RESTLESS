# 열린 결정 사항

AI는 아래 항목을 임의로 확정하지 않는다. 구현에 영향을 주면 사용자에게 확인한다.

| 항목 | 상태 |
|---|---|
| Java, Spring Boot 버전 | Java 21, Spring Boot 4.1.x로 결정 |
| base package 이름 | `com.restless`로 결정 |
| Gradle 단일 모듈 최종 합의 | 팀 확인 |
| ID 타입 | BIGINT AUTO_INCREMENT로 결정 ([ADR-0002](../decisions/0002-common-conventions.md)) |
| 공통 Order, Payment 테이블 스키마 | 결제 대상 식별과 결과 전달은 [ADR-0003](../decisions/0003-payment-module-boundary.md)으로 결정. 공통 Order, Payment 전체를 결제 담당이 맡는다. 컬럼 상세는 새 마이그레이션 PR에서 확정 |
| 티켓팅 결제 기한 인정 기준 | DECIDED: 기한 내 서버의 유효 승인 시도 기록·커밋. 늦은 성공 확인도 확정 ([상세](../domains/ticketing/transactions.md)) |
| 티켓팅 불명확 결제 운영 전환 | DECIDED: 최초 불명확 진입 후 5분이면 운영 확인 등록·알림, 자동 조회와 좌석 보호 지속 ([상세](../domains/ticketing/transactions.md)) |
| 티켓팅 멱등성 | DEFERRED: 전용 테이블·실행권·접수 작업 자동 인계. 설계는 보존. MVP 선점은 사용자별 키 UNIQUE+요청 해시, checkout은 예약 기준 중복 방지로 확정. 결제 키는 공통 담당자와 조율. PG 불명확 결과 재확인은 유지 ([상세](../domains/ticketing/idempotency.md)) |
| 티켓팅 결제 시도 식별 | DECIDED: payments.id를 모든 상태 변경 Handler에 전달. 공통 계약 반영: `PaymentAttemptContext(paymentId, approvalStartedAt)`, `payments.approval_started_at`, `RefundContext(paymentId, kind)` ([ADR-0003 4절](../decisions/0003-payment-module-boundary.md#4-개정-시도-문맥과-환불-종류-2026-11-12)). 예약 쪽 저장 구조는 티켓팅이 정한다 ([상세](../domains/ticketing/payment-handler.md)) |
| 팬미팅 결제 시도 ID | 팬미팅 문서의 `attempt_id VARCHAR(64)`, 결제 하나 아래 여러 시도 구조가 ADR-0003(시도마다 `payments` 행, `payments.id` = 시도 ID)과 다르다. 머지된 `fan_meeting_orders.approved_attempt_id VARCHAR(100)` 포함. 팬미팅 담당 확인 필요 |
| 드롭 결제 실패 후 재결제 | DECIDED (2026-11-12): 예약 기한 안이면 재고를 유지하고 재결제 허용 ([드롭 정책 8절](../domains/drop/policy.md#결제-실패와-재결제-decided-2026-11-12)) |
| 티켓팅·공통 결제 복구 계약 | 승인 시도 스키마, 전체 잠금 순서, 조회 간격·운영 담당·알림 채널, 미완료 시도 탐지, 안전한 재실행·최종 실패 조건 합의 필요 |
| Drop과 Concert 연결의 필수 여부 | 팀 확인 |
| 예약 TTL (초기 제안 5분) | PG UX 고려 후 확정 |
| 구매권 TTL | 미확정 |
| 재대기, 재입장 정책 | 미확정 |
| 대기열 FIFO 보장 수준 | 미확정 |
| MySQL Queue SQL과 Worker 구조 | 구현 설계 필요 |
| 판매 시작 직전 대기열 허용 여부 | 정책 확인 |
| 회원, 주최자 권한 범위 | 팀 확인 |
| 쿠폰의 드롭 결제 할인 연결 | 후순위 협의 |
| 공연장·좌석 배치 등록/선택 | DECIDED: 배치는 초기 데이터로 운영자 등록. 미등록 배치는 화면 없이 자료 전달·검토 후 등록. 주최자는 등록 배치 선택, 예매 오픈 이후 변경 금지 ([상세](../domains/ticketing/venue-consistency.md)) |
| 운영자 등록 화면의 실제 권한 | MVP 배치 관리자 화면은 제외. 초기 적재·추가 등록 절차, 공연장 등록 수단과 공통 역할 enum 매핑은 팀 확인 |
| 미등록 배치와 배치 버전 정책 | 자료 전달·검토 후 등록은 확정. 전달 채널·담당자, 오픈 전 교체 조건, 게시 배치 불변 버전은 후속 합의 |
| Spring 이벤트 후속 기능 선정 | 팀 확인 |
| 프론트엔드 정적 배포 가능 여부 | 저장소 확인 |
| Outbox/Kafka 채택 | [ADR-0001](../decisions/0001-event-handling.md) 재검토 조건에 따름 |
| PostgreSQL 전환 | 비교 실험 후 |
| Redis 대기열 전환 | 비교 실험 후 |
| 다중 EC2 도입 | 병목과 목표 확인 후 |

## Member 도메인 (검토 중)

검토 중인 필드: memberId, 이름, 닉네임, 프로필 사진, 이메일, 전화번호, 기본 배송지(Nullable), 팬클럽 가입 정보(Nullable), 결제수단 등록 여부, 역할과 권한

역할 후보: 시스템 관리자, 행사 주최자, 일반 사용자

미확정:

- 행사 주최자와 아티스트의 권한 관계
- 팬클럽 권한과 도메인별 혜택
- 프로필과 배송지 데이터 모델
- 등록 카드 처리 범위
- 주최자가 다른 도메인 상품을 어디까지 관리하는지

카드 정보는 직접 저장하지 않는다. 결제수단 저장이 필요하면 PG의 빌링, 토큰화 기능 지원 여부와 요구사항을 확인한다.
