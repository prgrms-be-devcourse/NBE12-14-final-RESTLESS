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
| 드롭 탈퇴 제한의 `PAID` 처리 (#41) | 제안: 실제 배송이 없으므로 `PAID` 주문은 탈퇴를 막지 않는다. 진행 중 거래(재고 점유·승인 중·결과 확인 중·보상 취소 중·환불 진행·환불 검토)만 막는다. 탈퇴 후 환불 경로 필요 여부 확인 ([드롭 정책 11절](../domains/drop/policy.md#11-탈퇴-제한-proposed-미구현)) |
| 티켓팅·공통 결제 복구 계약 | 승인 시도 스키마, 전체 잠금 순서(결제 쪽은 결정: 결제 준비를 포함해 결제 행 → 도메인 행, [ADR-0003](../decisions/0003-payment-module-boundary.md), #73), 조회 간격·운영 담당·알림 채널, 미완료 시도 탐지, 안전한 재실행·최종 실패 조건 합의 필요 |
| Drop과 Concert 연결의 필수 여부 | 팀 확인 |
| 예약 TTL (초기 제안 5분) | PG UX 고려 후 확정 |
| 구매권 TTL | DECIDED (2026-11-13): 입장 허가부터 5분 ([드롭 정책 7절](../domains/drop/policy.md#7-구매권-정책-decided-2026-11-13)) |
| 재대기, 재입장 정책 | DECIDED (2026-11-13): 상태 조회가 30초보다 오래 끊기면 이탈. 다시 들어오면 새 순번 ([드롭 정책 7절](../domains/drop/policy.md#7-구매권-정책-decided-2026-11-13)) |
| 대기열 FIFO 보장 수준 | DECIDED (2026-11-13): 대략적인 진입 순서. 정확한 선착순보다 처리 속도 우선 (`SKIP LOCKED` 배치) |
| MySQL Queue SQL과 Worker 구조 | 구현됨 (#70): `drop_queue_entries`, 입장 작업 `dropQueueAdmissionJob` ([transactions.md](../domains/drop/transactions.md), [data-model.md](../domains/drop/data-model.md)) |
| 판매 시작 직전 대기열 허용 여부 | 정책 확인. 현재 계약·구현은 판매 기간 밖 진입을 409 `DROP_NOT_ON_SALE`로 거절한다 ([drop.md](../contracts/api/drop.md) D4). 허용하려면 계약 변경 |
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
