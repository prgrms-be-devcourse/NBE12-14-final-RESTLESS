# 열린 결정 사항

AI는 아래 항목을 임의로 확정하지 않는다. 구현에 영향을 주면 사용자에게 확인한다.

## 1. 미결

팀 확인, 정책 확인, OPEN 항목이다. 구현에 영향이 있으면 먼저 사용자에게 묻는다.

| 항목 | 상태 |
|---|---|
| Gradle 단일 모듈 최종 합의 | 팀 확인 |
| 팬미팅 결제 시도 ID | 팬미팅 문서의 `attempt_id VARCHAR(64)`, 결제 하나 아래 여러 시도 구조가 ADR-0003(시도마다 `payments` 행, `payments.id` = 시도 ID)과 다르다. 머지된 `fan_meeting_orders.approved_attempt_id VARCHAR(100)` 포함. 팬미팅 담당 확인 필요 |
| 티켓팅, 공통 결제 복구 계약 | 승인 시도 스키마, 전체 잠금 순서(결제 쪽은 결정: 결제 준비를 포함해 결제 행 → 도메인 행, [ADR-0003](../decisions/0003-payment-module-boundary.md), #73), 조회 간격, 운영 담당, 알림 채널, 미완료 시도 탐지, 안전한 재실행, 최종 실패 조건 합의 필요. 공통 결제 쪽 조회 간격, 미완료 시도 탐지, 최종 실패 기준(NotFound 15분)은 #46에서 결정 ([재확인 규칙](../operations/failure-handling.md#미완료-결제-재확인-작업-46)). 남은 것은 티켓팅 쪽 항목 |
| Drop과 Concert 연결의 필수 여부 | 팀 확인. 현재 구현은 필수 (`drops.concert_id` NOT NULL FK, 등록 API 필수). 선택으로 바꾸려면 새 마이그레이션과 계약 변경 |
| 예약 TTL (초기 제안 5분) | PG UX 고려 후 확정 |
| 판매 시작 직전 입장 신청 허용 여부 | 정책 확인. 현재 계약·구현은 판매 기간 밖 진입을 409 `DROP_NOT_ON_SALE`로 거절한다 ([drop.md](../contracts/api/drop.md) D4). 허용하려면 계약 변경 |
| 회원, 주최자 권한 범위 | 팀 확인 |
| 쿠폰의 드롭 결제 할인 연결 | 후순위 협의 |
| 운영자 등록 화면의 실제 권한 | MVP 배치 관리자 화면은 제외. 초기 적재·추가 등록 절차, 공연장 등록 수단과 공통 역할 enum 매핑은 팀 확인 |
| 미등록 배치와 배치 버전 정책 | 자료 전달·검토 후 등록은 확정. 전달 채널·담당자, 오픈 전 교체 조건, 게시 배치 불변 버전은 후속 합의 |
| Spring 이벤트 후속 기능 선정 | 팀 확인 |
| 프론트엔드 정적 배포 가능 여부 | 저장소 확인 |
| 승인 후 확정이 계속 실패하는 결제의 처리 (#273) | OPEN: 지금은 자동으로 끝나지 않고 재시도 상한도 없다. 신호는 `payment.reconcile.failure{stage=apply}`와 ERROR 로그뿐이고 알림과 운영 절차가 없다 ([현재 동작](../operations/failure-handling.md#승인-결과-반영-실패와-복구-269-270-273)). 후보: 알림과 운영 절차, 횟수 상한 뒤 확인 필요 상태로 이동(기존 `UNKNOWN` 보호 상태 재사용 가능성 검토 필요), 자동 환불은 재고가 어긋난 원인을 모르는 채 환불하게 되어 위험 |
| 기한이 지난 READY 결제를 닫는 작업의 후보 막힘 (#275) | OPEN: `expires_at` 순서로 후보를 읽어서 닫기가 계속 실패하면 뒤의 만료가 막힐 수 있다. 재현만 했다. 해결하려면 후보 조회 조건과 정렬, 인덱스를 바꿔야 한다 |
| 다계정 재고 선점 대응 | OPEN: 사용자당 슬롯은 한 계정의 중복 구매만 막는다. 계정을 여러 개 만들면 각자 대기열에 들어갈 수 있다. 후보: 가입 인증 강화, 가입 후 경과 기간 제한, IP나 기기당 입장 수 제한. 사업 정책이라 임의로 정하지 않는다 |

## 2. 제안

PROPOSED 항목이다. 승인 전에는 결정으로 보지 않는다.

| 항목 | 상태 |
|---|---|
| 요청 횟수 제한의 기본 한도 (#271) | PROPOSED: 로그인 분당 20, 가입 분당 10, 갱신 분당 30, 대기열 진입 10초당 5, 상태 조회 초당 5 ([ADR-0004](../decisions/0004-request-rate-limit.md)). 같은 공유기를 쓰는 시연 환경에서는 조정이 필요할 수 있다. 값은 설정으로 바꾼다 |

## 3. 비교 실험 뒤 결정

측정이나 재검토 조건을 기다리는 항목이다.

| 항목 | 상태 |
|---|---|
| Outbox/Kafka 채택 | [ADR-0001](../decisions/0001-event-handling.md) 재검토 조건에 따름 |
| PostgreSQL 전환 | 비교 실험 후 |
| Redis 입장 제어 전환 | 비교 실험 후. 대기실을 Redis로 옮기는 안은 [ADR-0006](../decisions/0006-drop-waiting-room-redis.md) PROPOSED 초안이다. 결정은 사람이 하며 승인 전에는 결정으로 보지 않는다 |
| 다중 EC2 도입 | 병목과 목표 확인 후 |

## 4. 결정됨

결정한 항목의 기록이다. 상세는 각 링크의 정본 문서를 따른다.

| 항목 | 상태 |
|---|---|
| Java, Spring Boot 버전 | Java 21, Spring Boot 4.1.x로 결정 |
| base package 이름 | `com.restless`로 결정 |
| ID 타입 | BIGINT AUTO_INCREMENT로 결정 ([ADR-0002](../decisions/0002-common-conventions.md)) |
| 공통 Order, Payment 테이블 스키마 | 결제 대상 식별과 결과 전달은 [ADR-0003](../decisions/0003-payment-module-boundary.md)으로 결정. 공통 Order, Payment 전체를 결제 담당이 맡는다. 컬럼은 마이그레이션으로 확정 (V20261112_1000, V20261112_1900, V20261113_1000, V20261113_1300) |
| 티켓팅 결제 기한 인정 기준 | DECIDED: 기한 내 서버의 유효 승인 시도 기록·커밋. 늦은 성공 확인도 확정 ([상세](../domains/ticketing/transactions.md)) |
| 티켓팅 불명확 결제 운영 전환 | DECIDED: 최초 불명확 진입 후 5분이면 운영 확인 등록·알림, 자동 조회와 좌석 보호 지속 ([상세](../domains/ticketing/transactions.md)) |
| 티켓팅 결제 시도 식별 | DECIDED: payments.id를 모든 상태 변경 Handler에 전달. 공통 계약 반영: `PaymentAttemptContext(paymentId, approvalStartedAt)`, `payments.approval_started_at`, `RefundContext(paymentId, kind)` ([ADR-0003 4절](../decisions/0003-payment-module-boundary.md#4-개정-시도-문맥과-환불-종류-2026-11-12)). 예약 쪽 저장 구조는 티켓팅이 정한다 ([상세](../domains/ticketing/payment-handler.md)) |
| 드롭 결제 실패 후 재결제 | DECIDED (2026-11-12): 예약 기한 안이면 재고를 유지하고 재결제 허용 ([드롭 정책 8절](../domains/drop/policy.md#결제-실패와-재결제-decided-2026-11-12)) |
| 드롭 탈퇴 제한의 `PAID` 처리 (#41) | DECIDED (2026-11-14): `PAID` 주문은 탈퇴를 막지 않는다. 진행 중 거래만 막는다. 탈퇴 요청에 결제 완료 주문 안내 동의를 받고, 탈퇴 회원의 환불 요청 경로는 만들지 않는다 (#97, [드롭 정책 11절](../domains/drop/policy.md#11-탈퇴-제한-decided-2026-11-14)) |
| 입장 허가 유효시간 | DECIDED (2026-11-13): 입장 허가부터 5분 ([드롭 정책 7절](../domains/drop/policy.md#7-입장-허가-정책-decided-2026-11-13)) |
| 재대기, 재입장 정책 | DECIDED (2026-11-13): 상태 조회가 30초보다 오래 끊기면 이탈. 다시 들어오면 새 입장 순번 ([드롭 정책 7절](../domains/drop/policy.md#7-입장-허가-정책-decided-2026-11-13)) |
| 입장 제어 FIFO 보장 수준 | DECIDED (2026-11-13): 대략적인 진입 순서. 정확한 선착순보다 처리 속도 우선. 2026-11-19부터 순서는 SKU별이며 DB 락 획득 순서를 구매 배정 순서로 쓰지 않는다 |
| MySQL Queue SQL과 Worker 구조 | 구현됨 (#70): `drop_queue_entries`, 입장 허가 작업 `dropQueueAdmissionJob` ([transactions.md](../domains/drop/transactions.md), [data-model.md](../domains/drop/data-model.md)) |
| 드롭 SKU별 구매 입장 제어 (#253) | DECIDED (2026-11-19, 같은 날 개정): 진입 때 SKU를 고르고 `variant_id`를 저장하며, 입장 순번과 입장은 SKU별이다. 추가 입장 허가 가능 수는 `max(0, available - 미사용 유효 ADMITTED 수)`이고 기존 배치 제한도 적용한다. 동시 입장 허가 작업이 초과 허가하면 안 된다. SKU의 `available = 0`이면 `reserved`와 무관하게 신규 진입과 입장 허가를 중단하고 기존 `WAITING`과 미사용 `ADMITTED`를 종료한다. 이는 현재 구매 불가이며 최종 매진이 아니다. 재고가 반환되면 판매 기간 안에서 새 진입으로 재개하고 종료된 입장 순번과 입장 허가는 복구하지 않는다. 이미 점유한 구매의 결제, 기한, 재결제, 복구는 그대로다. #254에서 구현했다 ([드롭 정책 7-1절](../domains/drop/policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)). 먼저 정한 안(`reserved > 0`이면 대기 유지)은 폐기했다 ([LEGACY-12](../decisions/superseded.md)) |
| 드롭 품절 뒤 대기자 처리 (이전 OPEN 항목) | DECIDED (2026-11-19): 위 항목으로 결정됐다. `available = 0`이면 대기를 종료한다. 이전 성능 실험(2026-11-18)은 옛 동작(판매 기간 종료 전에는 품절만으로 대기를 닫지 않음)으로 측정했다 ([실험 계획](../testing/drop-performance-plan.md)) |
| 드롭 대기 종료 상태와 API (#253) | DECIDED (2026-11-19): 새 대기 상태 `UNAVAILABLE`(후보 비교는 [드롭 정책 7-1절](../domains/drop/policy.md#7-1-sku별-구매-입장-제어-decided-2026-11-19-253)). `GET .../queue/me`는 200 `UNAVAILABLE`, `POST .../queue/entries`는 `available = 0`이면 409 `DROP_SOLD_OUT`(메시지 현재 구매 불가), 종료된 입장 허가의 점유 요청은 403 `DROP_ADMISSION_REQUIRED`. 구현 때 CHECK 제약을 바꾸는 migration이 필요하다 |
| 드롭 대기 종료의 동시성 처리 방식 (#253) | DECIDED (2026-11-19): 같은 SKU의 입장과 종료 트랜잭션을 DB 락으로 한 번에 하나씩 직렬화한다. 이미 처리 중이면 기다리지 않고 그 SKU만 건너뛴다. 서버 안 메모리 락은 쓰지 않는다. 사용자 구매(TX1)는 이 락을 잡지 않는다. 구체 수단은 #254에서 게이트 행(`drop_queue_gates`)으로 구현했다. 종료는 입장 허가 작업이 `available = 0`을 관찰했을 때 수행하며 진입 직후 입장 허가 작업 한 번의 주기만큼 지연을 허용한다. 입장 허가 작업 주기는 1초다(2026-11-19 결정, 현재 기본값 유지) ([transactions.md D4](../domains/drop/transactions.md)) |
| 드롭 같은 회원의 SKU 변경 진입 (#253) | DECIDED (2026-11-19): 새 SKU로 새로 `WAITING`하고 기존 SKU의 대기를 뺀다(`CLOSED`). 새 SKU가 `available = 0`이면 거절하고 기존 대기를 유지한다. 페이지를 나가면 기존 heartbeat 규칙(30초)으로 해제한다. 기존 대기가 `ADMITTED`(미사용 입장 허가)일 때도 같은 처리이며 2026-11-19에 사람이 확인했다 |
| 드롭 입장 허가와 다른 SKU 점유 요청의 오류 코드 (#253) | DECIDED (2026-11-19): 403 `DROP_ADMISSION_REQUIRED`. 정상 화면에서는 생기지 않는 요청을 막는 방어 검사다 |
| 드롭 대기열 조회 간격 제안과 조기 거절 (P3) | DECIDED (2026-11-24, 사용자 위임 결정, 사람이 나중에 검토): 서버가 응답 `pollAfterMs`로 다음 조회 간격을 제안한다. WAITING 순번 1~20은 1초, 21~200은 2초, 201~1000은 3초, 그 뒤 5초이고 상한은 10초다. 진입과 상태 조회는 서버별 동시 처리 상한을 넘으면 503 `QUEUE_BUSY`와 `Retry-After`로 바로 거절한다. 구현할 때 [policy.md 7-1절](../domains/drop/policy.md)과 [drop.md D4](../contracts/api/drop.md)의 조회 간격은 클라이언트 제안값이라는 문장과 [실행 절차 4절](../testing/drop-performance-procedure.md#4-대표-판매-측정)과 5절의 2초 조회 가정도 함께 고친다. 구현은 1단계 [#390](https://github.com/prgrms-be-devcourse/NBE12-14-final-RESTLESS/issues/390)이며 아직 구현 전이다. 요청 횟수 제한의 기본 한도(#271)는 이 결정과 별개이고 2절 PROPOSED 그대로다 |
| 공연장·좌석 배치 등록/선택 | DECIDED: 배치는 초기 데이터로 운영자 등록. 미등록 배치는 화면 없이 자료 전달·검토 후 등록. 주최자는 등록 배치 선택, 예매 오픈 이후 변경 금지 ([상세](../domains/ticketing/venue-consistency.md)) |

## 5. 보류

DEFERRED 항목이다.

| 항목 | 상태 |
|---|---|
| 티켓팅 멱등성 | DEFERRED: 전용 테이블·실행권·접수 작업 자동 인계. 설계는 보존. MVP 선점은 사용자별 키 UNIQUE+요청 해시, checkout은 예약 기준 중복 방지로 확정. 결제 키는 공통 담당자와 조율. PG 불명확 결과 재확인은 유지 ([상세](../domains/ticketing/idempotency.md)) |

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
