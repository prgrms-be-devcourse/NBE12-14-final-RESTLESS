# 티켓팅 PaymentResultHandler 연결

- 상태: 상태 전이는 DECIDED (2026-11-12, 담당자 합의).
- 기준: ADR-0003의 동기 Handler 계약. 승인 시도 저장 및 내부 서비스의 대상 조회·승인 시작·성공 확정/발급을 구현했다. PaymentResultHandler 빈 등록과 실패/불명확/만료/보상 처리는 아직 다음 단계다.
- paymentId를 모든 상태 변경 Handler에 전달하는 원칙은 DECIDED (2026-11-12). 공통 DTO·인터페이스는 ADR-0003 개정에 반영됐고 티켓팅 예약의 시도 저장·수락 도메인 메서드를 구현했다. 실제 Handler 연결은 후속 단계다. 결제 대상 매핑은 예약 ID 기준으로 확정했다 (2026-11-13, 사용자 합의).

## 경계

결제 모듈이 결제 행을 먼저 잠근 뒤 동일 DB 트랜잭션 안에서 티켓팅 Handler를 호출한다.
티켓팅 내부 잠금은 구매 관리 → 예약 → 좌석 ID 오름차순이다. Handler에서 결제 행을 다시 잠그거나 PG를 호출하지 않는다.
주문 변경은 주문 Application Service로 처리한다. 결제·주문·예약·좌석·카운터·티켓은 함께 커밋/롤백한다.
알림은 AFTER_COMMIT 후속 작업이다.
결제 대상은 (TICKET, 예약 ID)로 식별한다 (DECIDED, 2026-11-13). 기존 공통 주문 연결은 유지하되 payment.order_id 물리 연결을 전제하지 않는다.

## 확정된 상태 전이

| Handler | 검증·처리 | 예약·좌석 |
|---|---|---|
| onApprovalStarting | 결제 가능 상태·기한·좌석 소유권 검증 후 ACCEPT, 불가면 REJECT | PAYMENT_PENDING/HELD 유지. 승인 시도 기록과 함께 커밋하여 보호 |
| onApproved | 기한 내 유효 시도·대상·금액·현재 소유권 검증 | PAYMENT_PENDING 또는 RECONCILING → CONFIRMED, 선점 CONFIRMED, 좌석 SOLD, reserved 감소·confirmed 증가, UUID 티켓 발급 |
| onFailed | 해당 시도 최종 실패 확인, 다른 미완료 시도 없음과 현재 기한 검증 | 기한 전 PAYMENT_PENDING/HELD 유지. 기한과 같거나 이후 RELEASED, 선점 RELEASED·좌석 반환·reserved 감소 |
| onUnknown | 해당 시도의 결과 불명확 기록 | PAYMENT_PENDING → RECONCILING. 좌석 HELD와 reserved 유지 |

예약에 승인 중 상태를 추가하지 않는다. 실제 승인 시작 여부는 영속 승인 시도 기록으로 구분한다.
성공 확인 시각이 늦어도 기한 내 기록·커밋된 유효 시도라면 확정한다. PG approvedAt으로 티켓팅 기한을 판정하지 않는다.
같은 시도의 성공·실패·불명확 결과는 조건부 전이로 중복 변경하지 않는다. 취소 완료 상태를 과거 성공으로 되돌리지 않는다.
DB 오류 등 기술 실패는 예외로 전체 롤백한다. 업무상 승인 불가는 REJECT로 전달하고 공통 결제가 보상 취소를 조정한다.
불명확 상태 최초 진입 후 5분이면 운영 확인 등록·알림, 자동 조회와 좌석 보호는 계속한다.

## 현재 공통 계약 (ADR-0003 반영)

- onApprovalStarting/onFailed/onUnknown은 PaymentAttemptContext(paymentId, approvalStartedAt)를 받는다.
- onApproved의 ApprovedPayment에는 paymentId, approvalStartedAt, PG approvedAt이 있다. 티켓팅은 PG approvedAt으로 기한을 다시 판정하지 않는다.
- onApprovalStarting은 도메인 잠금 획득 뒤 읽은 DB UTC 현재 시각으로 기한을 검사한다. 전달받은 approvalStartedAt은 잠금 대기 전 값이므로 신규 시도 허용 판정에 쓰지 않는다.
- onFailed의 자원 반환 여부는 도메인 정책에 따른다. 티켓팅은 기한 내 재시도를 허용하고, 진행 중·불명확 결과를 실패로 간주하지 않는다.
- onRefundRequesting/onRefundRejected/onRefunded는 RefundContext(paymentId, kind)를 받는다. 문맥 자체는 PG 취소 확정을 뜻하지 않으며 CUSTOMER_REFUND와 COMPENSATION을 구분한다.
- onRefundRequesting은 손님 환불 요청 트랜잭션에서 가능 여부를 검사한다. 기본 구현은 REFUND_NOT_SUPPORTED로 거절하며, 도메인이 수락할 경우 환불 대기 전환과 공통 결제·환불 기록을 함께 커밋한다. 좌석은 반환하지 않는다.
- onRefundRejected는 손님 환불의 PG 최종 거절 시 결제 APPROVED 복귀·환불 FAILED와 같은 트랜잭션에서 호출된다. 기본은 no-op이며, 티켓팅의 환불 상태 처리 연결은 후속이다. 두 콜백은 보상 취소에 호출하지 않는다.
- 실제 자원 복원은 onRefunded에서만 수행한다. 공통 손님 환불 API가 추가됐어도 티켓팅 환불·Handler가 구현된 것은 아니다.
- 공통 결제는 payableType/payableId로 식별한다. payments.order_id를 요구하지 않는다.
- 티켓팅 Handler와 승인 시도 연결은 아직 구현하지 않았다. checkout은 공통 주문 준비까지만 수행하며 payments 생성·PG 호출·예매 확정을 하지 않는다.

## 검증 계획 (미실행)

- 승인 시작 시 예약 상태 유지, 기한/소유권 불일치 REJECT, PG 호출 금지.
- 기한 내 유효 시도의 늦은 성공 확정, 티켓/카운터 한 번만 반영.
- 최종 실패의 기한 전/정각/이후 분기와 재시도 가능 상태.
- 불명확 결과 중복 처리: 최초 시각 유지, 자원 반환 없음.
- 이전 시도의 늦은 실패·불명확 결과가 새 시도·확정 예약을 변경하지 않음.
- 과거 시도의 상충하는 성공은 단순 무시하지 않고 공통 결제에 조사·보상 대상으로 남김.

## 승인 시도 식별 (DECIDED, 2026-11-12)

- payments.id를 승인 시도 ID로 사용하며 별도 시도 UUID는 추가하지 않는다.
- onApprovalStarting/onApproved/onFailed/onUnknown/onRefundRequesting/onRefundRejected/onRefunded 모두 해당 paymentId를 전달한다.
- type과 load는 상태 변경 Handler가 아니다. load는 결제 생성 전 대상 조회이므로 paymentId를 요구하지 않는다.
- 같은 시도의 결과 조회·복구는 같은 ID를 사용하고, 최종 실패 후 새 시도는 새 payments 행과 ID를 사용한다.
- 결제 모듈은 잠근 paymentId와 payableType/payableId 관계를 확인한다. 정상 응답·재조회·운영 복구 모두 같은 ID를 전달한다.
- 티켓팅은 현재 시도와 예약 상태를 함께 확인한다. 이전 시도 실패·불명확 결과로 새 시도를 변경하지 않는다.
- 이전 시도의 상충하는 성공은 단순 무시하지 않고 조사·보상 대상으로 남긴다.

## 티켓팅 후속 구현

공통 계약은 이미 Accepted ADR-0003과 코드에 반영돼 있다. 공유 인터페이스 변경은 필요하지 않다.
티켓팅은 승인 시작을 받아들인 paymentId와 시각을 영속화하고 결과가 같은 시도인지 확인해야 한다.
예약의 current_payment_id, approval_started_at, payment_result_pending을 V20261113_1500으로 추가했다. current_payment_id는 공통 payments.id의 스칼라 문맥으로 저장하며 물리 FK를 추가하지 않는다. 실제 대상·시도 관계는 공통 결제의 잠금·callback 계약과 티켓팅 Handler 검증으로 보장한다.
current_payment_id는 NULL 허용 UNIQUE이며, DB CHECK는 ID/시각의 부분 저장과 checkout 이전·결제 기한 이상인 승인 시작 시각을 막는다. 아직 시도가 없는 기존 예약은 NULL/NULL/false이며 과거 시도를 추정해 채우지 않는다.
payment_result_pending은 수락한 승인 결과 또는 필요한 보상 취소가 미확정임을 뜻한다. 예약 상태 PAYMENT_PENDING은 checkout 직후와 승인 진행 중 모두 쓰므로 이를 구분하는 별도 필드다. 성공 확정에서는 이 필드를 false로 바꾸고 시도 ID/시각을 보존한다. 실패/보상 종료와 만료 판단에 사용하는 Handler·만료 연결은 후속 단계다.
승인 전 READY 만료 실패의 approvalStartedAt은 null일 수 있다. 정상 환불과 보상 취소를 구분하고 예매 확정 전 보상 취소에 confirmed_count 감소를 적용하지 않는다.

## 공통 대사와 checkout의 관계

공통 결제는 PaymentReconciler/PaymentReconciliationScheduler로 미완료 승인·취소를 재조회하고 같은 Handler로 결과를 반영한다. 기준과 주기는 [공통 대사 운영 계약](../../operations/failure-handling.md#공통-결제-대사-46)을 따른다.
checkout은 payments 행을 생성하지 않으므로 공통 대사 후보가 아니다. 주문만 연결된 PAYMENT_PENDING 예약의 결제 기한 만료를 공통 대사가 대신 정리하지 않는다. 티켓팅 Handler·결제 시도 연동과 결제 이후 만료 처리는 여전히 후속 구현이다.
공통 UNKNOWN 최초 시각·나이 메트릭은 구현됐지만 티켓팅의 불명확 5분 운영 확인 등록·중복 없는 알림은 후속이며 좌석 보호 정책을 바꾸지 않는다.

## 단계별 구현: 승인 시도 기록 기반 (2026-11-13)

TicketReservation.startApproval(paymentId, attemptStartedAt, now)는 호출자가 구매 관리 행·예약·좌석 잠금과 현재 소유권 검증을 끝낸 후 호출한다. 메서드 자체가 잠금을 획득하거나 공통 주문 상태를 변경하지 않는다.

- 최초 수락은 checkout 완료된 PAYMENT_PENDING과 now < payment_expires_at을 요구한다. now는 호출자가 잠금 후 읽은 DB UTC 시각이다.
- 공통 PaymentAttemptContext의 approvalStartedAt을 원래 값으로 보존한다. 이 값은 checkout 이후이며 판정 시각보다 미래가 아니어야 한다. 잠금 후 시각으로 덮어쓰지 않아 이후 callback과 같은 문맥으로 대조할 수 있다.
- 동일 ID·시각의 기한 내 재수신은 기록을 유지한다. 동일 ID의 다른 시각이나 미확정 상태에서 다른 ID는 거절한다. 수락 여부를 boolean으로 반환하며 업무상 거절에서는 필드를 변경하지 않는다.
- 예약은 PAYMENT_PENDING을 유지하고 최초 checkout 시각·결제 기한을 변경하지 않는다. matchesPaymentAttempt는 결과의 ID·시각이 수락한 기록과 같은지 검사한다.
- Handler 구현·빈 등록은 아직 하지 않았다. 따라서 새 공통 결제 시도 생성·PG 승인·티켓 발급·실패/불명확/만료/보상 처리까지 연결됐다는 의미가 아니다.

집중 검증: TicketReservationPaymentAttemptTest 9개, TicketReservationMappingTest 13개, TicketCheckoutServiceTest 13개 통과. 새 Flyway 적용과 JPA validate를 포함한다.

## 단계별 구현: 대상 조회·승인 시작 검증 (2026-11-13)

TicketPaymentService에 아래 두 메서드를 구현했다. 아직 PaymentResultHandler를 구현하거나 Registry에 TICKET Handler를 등록하지 않았으므로 실제 공통 결제 API와 연결되지 않았다.

### load(예약 ID)

- 공통 결제의 준비 트랜잭션에 MANDATORY로 참여하는 읽기 전용 조회다. 결제 행을 잠그기 전 호출되므로 도메인 쓰기 잠금을 획득하지 않는다.
- checkout 완료된 PAYMENT_PENDING/RECONCILING 예약과 연결된 TICKET 주문을 조회한다. 소유자·종류·주문 존재 불일치는 데이터 오류다.
- 주문 상태가 PENDING_PAYMENT/PAYMENT_CONFIRMING/PAYMENT_REVIEW이고 DB UTC 현재 시각이 최초 결제 기한 미만이면 PayableSnapshot(소유자, 주문 총액, 서버 회차 ID 기반 상품명, 최초 기한)을 반환한다. 없는/종료된/기한 지난 대상은 empty다.
- 진행 중·불명확 대상도 조건을 만족하면 정보를 반환해 공통 결제가 활성 시도의 PROCESSING/UNKNOWN 중복 방지 규칙을 적용할 수 있게 한다. snapshot은 승인 허가가 아니다.
- 0원은 snapshot 금액 그대로 전달한다. 공통 결제의 FREE_ORDER 거절을 유지하며 무료 예매를 자동 확정하지 않는다.

### onApprovalStarting(예약 ID, PaymentAttemptContext)

- 호출자가 공통 결제 행을 먼저 잠근 트랜잭션에 MANDATORY로 참여한다. 티켓팅이 새 트랜잭션을 열거나 결제 Repository를 직접 사용하지 않는다.
- 구매 관리 행 → 예약 → 좌석 ID 오름차순으로 잠그고 기존 OrderService.find로 주문 정보를 확인한다. 승인 시도 수락 후 기존 changeStatus가 주문을 잠그므로 전체 잠금 순서는 결제 행 → 구매 관리 행 → 예약 → 좌석 → 주문을 유지한다. 공통 주문 서비스에 새 메서드를 추가하지 않는다.
- 기한은 티켓팅 행 잠금과 업무 검증 후 승인 시도를 기록하는 DB UTC 시각(now < payment_expires_at)으로 판정한다. 기록 이후 주문 잠금 대기로 기한이 지나도 준비 트랜잭션이 성공하면 유효한 시도다. 최초 hold_expires_at은 결제 진입 후의 승인 기한으로 쓰지 않는다.
- PAYMENT_PENDING·checkout 필드·시도 문맥·현재 HELD/선점 포인터·동일 예약/회차·선점 수량과 카운터·선점 가격 합계와 주문 총액을 재검증한다. 최초 시도의 주문은 PENDING_PAYMENT, 같은 미확정 시도의 재수신은 PAYMENT_CONFIRMING이어야 한다.
- 업무상 불가면 ApprovalDecision.reject(reason)을 반환한다. 이유는 TICKET_RESERVATION_NOT_FOUND, TICKET_NOT_PAYABLE, TICKET_PAYMENT_ATTEMPT_IN_PROGRESS, TICKET_PAYMENT_EXPIRED, TICKET_ORDER_NOT_PAYABLE, TICKET_SEAT_OWNERSHIP_LOST, TICKET_INVALID_PAYMENT_ATTEMPT, FREE_ORDER다.
- 참조·회차·금액·수량 불일치와 DB 오류는 예외로 전체 롤백한다. 미확정 시도를 다른 ID로 덮어쓰거나 주문·좌석·카운터를 임의 보정하지 않는다.
- 수락 시 기존 startApproval로 원래 시도 문맥을 저장하고 OrderService.changeStatus로 PAYMENT_CONFIRMING을 반영한다. 예약 PAYMENT_PENDING·좌석 HELD·현재 소유 포인터·카운터·최초 기한은 유지한다. 같은 시도의 반복 수락은 시각을 갱신하지 않는다.
- PG 호출·실패/불명확/성공·티켓 발급·결제 이후 만료·보상 취소는 아직 연결하지 않았다. 이 서비스 내부에서 잠금 실패 재시도를 하지 않고 공통 결제의 트랜잭션 경계를 따른다.

대상 조회·승인 시작 서비스의 최초 구현 때는 사용자 요청으로 컴파일만 확인했다. 이후 요청에 따라 필요한 통합 테스트를 추가했으며 아래 검증 기록을 따른다. 앞선 439개 통과 기록은 승인 시도 저장 기반 단계의 결과다.

## 대상 조회·승인 시작 필수 검증 (2026-11-13)

TicketCheckoutServiceTest.ApprovalPreparation에 기존 공연·예약 fixture를 재사용해 현재 6개 메서드/10개 실행 사례를 유지한다. 최초 9개에서 기한 판정을 티켓팅 기록 시점으로 명확히 하며 실제 예약 잠금 대기 사례 1개를 추가했다.

- snapshot의 소유자·주문 총액·상품명·최초 기한을 검증한다. 다른 트랜잭션이 구매 관리/예약/좌석/주문을 잠근 동안에도 조회가 끝나고 상태가 변하지 않는지 확인한다.
- 서로 다른 두 승인 시도의 동시 요청은 하나만 수락하고, 수락한 시도 재수신은 주문 PAYMENT_CONFIRMING·원래 문맥·최초 기한을 유지하는지 확인한다.
- 조회 후 좌석 소유권, 주문 금액, 주문 소유자, 선점 수량이 바뀐 4가지 경우를 승인 시작에서 재검증한다. 오류를 이유로 수량을 임의 보정하거나 시도를 기록하지 않는다.
- 실제 예약 쓰기 잠금을 유지한 채 기한을 넘긴 후 풀면 승인 시작을 거절한다. 반대로 티켓팅 잠금 아래 기한 내 시도를 기록하고 주문 쓰기 잠금에서 대기한 경우에는 기한 경과 후에도 원래 시도·기한을 유지한 채 수락한다. 주문 잠금은 테스트 fixture SQL로만 재현하며 운영 코드는 기존 주문 API를 사용한다. 일반 기한 경계의 반복 사례는 기존 도메인 테스트를 재사용한다.
- 승인 기록과 주문 상태를 실제 flush한 뒤 호출자 트랜잭션을 실패시켜 두 변경이 함께 롤백되는지 확인한다.
- 대상 조회·승인 시작의 MANDATORY를 확인한다. 추가했던 주문 잠금 조회 메서드와 그 전용 assertion은 제거했으며 공통 OrderService/OrderServiceTest는 원래 버전과 같다. 기존 changeStatus의 트랜잭션 계약 테스트를 재사용한다.

TicketReservationPaymentAttemptTest에서 0번 ID 거절만 확인하는 단순 방어 로직 테스트 1개를 제거했다. 현재 도메인 테스트는 8개이며 시도 식별·덮어쓰기 방지·기한 경계·상태 검증은 유지한다. checkout의 인증·주문 생성·중복 요청과 기존 매핑 제약 테스트는 복제하지 않았다.
최초 9개 서비스 사례의 집중 실행: `./gradlew test --tests 'com.restless.ticketing.application.TicketCheckoutServiceTest*' --tests 'com.restless.ticketing.domain.TicketReservationPaymentAttemptTest' --tests 'com.restless.order.OrderServiceTest'` — 37개 통과, 실패·오류·건너뜀 0개. 기존 주문 API 전환 후에는 같은 명령으로 38개가 통과했다.
이 검증은 내부 서비스까지이며 Handler 등록·실제 PG 승인·성공 발급·실패/불명확/만료/보상 처리의 검증은 아니다.

## 단계별 구현: 승인 성공과 티켓 발급 (2026-11-13)

TicketPaymentService.onApproved(예약 ID, ApprovedPayment)를 추가했다. 공통 트랜잭션에 MANDATORY로 참여하며 기존 OrderService.find/changeStatus만 사용한다. 공통 주문 서비스·결제 인터페이스는 변경하지 않았다.

- 수락한 paymentId/approvalStartedAt과 성공 문맥·주문 금액을 대조하고, PAYMENT_PENDING 또는 RECONCILING의 미확정 시도만 최초 확정한다. 현재 시각·PG 승인 시각으로 기한을 다시 판정하지 않는다.
- 구매 관리 → 예약 → 좌석 ID 오름차순으로 잠근 뒤 모든 좌석의 현재 선점 소유권·이력·수량/금액을 검증한다. 성공 반영은 예약/선점 CONFIRMED·좌석 SOLD·구매 수량 이동·ISSUED 티켓·주문 PAID이며 중간 실패 시 전체 롤백한다.
- 같은 시도로 이미 CONFIRMED이면 기존 티켓·소유권·주문·수량이 일관된지 확인하고 ACCEPT한다. 결과 재수신으로 티켓이나 수량을 바꾸지 않는다. 종료된 예약을 과거 성공으로 되돌리지 않는다.
- 업무 거절은 TICKET_RESERVATION_NOT_FOUND, TICKET_PAYMENT_ATTEMPT_MISMATCH, TICKET_NOT_CONFIRMABLE, TICKET_PAYMENT_AMOUNT_MISMATCH, TICKET_ORDER_NOT_CONFIRMABLE, TICKET_SEAT_OWNERSHIP_LOST를 reason으로 REJECT한다. DB/참조/금액 합계/수량/이미 확정된 기록 불일치는 예외로 전체 롤백한다.
- 발급 상태 ISSUED는 사용자 합의로 확정했다. 기존 티켓 DB 제약을 사용하며 새 마이그레이션은 없다. 상세 발급·검증 범위는 [티켓 발급 문서](ticket-issuance.md#승인-성공-내부-서비스와-발급-상태-2026-11-13)를 따른다.

아직 PaymentResultHandler 빈은 등록하지 않았다. 내부 성공 처리가 실제 PG API·실패/불명확·만료·보상 취소까지 연결된 것은 아니다. 다음 단계에서 나머지 결과 처리와 함께 등록한다.
