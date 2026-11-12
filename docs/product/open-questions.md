# 열린 결정 사항

AI는 아래 항목을 임의로 확정하지 않는다. 구현에 영향을 주면 사용자에게 확인한다.

| 항목 | 상태 |
|---|---|
| Java, Spring Boot 버전 | Java 21, Spring Boot 4.1.x로 결정 |
| base package 이름 | `com.restless`로 결정 |
| Gradle 단일 모듈 최종 합의 | 팀 확인 |
| ID 타입 | BIGINT AUTO_INCREMENT로 결정 ([ADR-0002](../decisions/0002-common-conventions.md)) |
| 공통 Order, Payment 테이블 스키마 | 결제 대상 식별과 결과 전달은 [ADR-0003](../decisions/0003-payment-module-boundary.md)으로 결정. 공통 Order, Payment 전체를 결제 담당이 맡는다. 컬럼 상세는 새 마이그레이션 PR에서 확정 |
| Drop과 Concert 연결의 필수 여부 | 팀 확인 |
| 예약 TTL (초기 제안 5분) | PG UX 고려 후 확정 |
| 구매권 TTL | 미확정 |
| 재대기, 재입장 정책 | 미확정 |
| 대기열 FIFO 보장 수준 | 미확정 |
| MySQL Queue SQL과 Worker 구조 | 구현 설계 필요 |
| 판매 시작 직전 대기열 허용 여부 | 정책 확인 |
| 회원, 주최자 권한 범위 | 팀 확인 |
| 쿠폰의 드롭 결제 할인 연결 | 후순위 협의 |
| 운영자 등록 화면의 실제 권한 | 팀 확인 |
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
