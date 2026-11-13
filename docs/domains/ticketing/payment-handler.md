# 티켓팅 PaymentResultHandler 연결

- 상태: 상태 전이는 DECIDED (2026-11-12, 담당자 합의).
- 기준: ADR-0003의 동기 Handler 계약. 코드는 아직 구현하지 않았다.
- paymentId를 모든 상태 변경 Handler에 전달하는 원칙은 DECIDED (2026-11-12). 공통 DTO·인터페이스는 ADR-0003 개정에 반영됐고 티켓팅의 시도 저장·검증은 후속 구현이다. 결제 대상 매핑은 제안 상태다.

## 경계

결제 모듈이 결제 행을 먼저 잠근 뒤 동일 DB 트랜잭션 안에서 티켓팅 Handler를 호출한다.
티켓팅 내부 잠금은 구매 관리 → 예약 → 좌석 ID 오름차순이다. Handler에서 결제 행을 다시 잠그거나 PG를 호출하지 않는다.
주문 변경은 주문 Application Service로 처리한다. 결제·주문·예약·좌석·카운터·티켓은 함께 커밋/롤백한다.
알림은 AFTER_COMMIT 후속 작업이다.
결제 대상은 (TICKET, 예약 ID) 매핑을 제안한다. 기존 공통 주문 연결은 유지하되 payment.order_id 물리 연결을 전제하지 않는다.

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
예약에 current_payment_id와 승인 시작 기록 시각을 저장하는 방안은 제안이며 실제 컬럼·FK는 후속 설계 대상이다.
승인 전 READY 만료 실패의 approvalStartedAt은 null일 수 있다. 정상 환불과 보상 취소를 구분하고 예매 확정 전 보상 취소에 confirmed_count 감소를 적용하지 않는다.

## 공통 대사와 checkout의 관계

공통 결제는 PaymentReconciler/PaymentReconciliationScheduler로 미완료 승인·취소를 재조회하고 같은 Handler로 결과를 반영한다. 기준과 주기는 [공통 대사 운영 계약](../../operations/failure-handling.md#공통-결제-대사-46)을 따른다.
checkout은 payments 행을 생성하지 않으므로 공통 대사 후보가 아니다. 주문만 연결된 PAYMENT_PENDING 예약의 결제 기한 만료를 공통 대사가 대신 정리하지 않는다. 티켓팅 Handler·결제 시도 연동과 결제 이후 만료 처리는 여전히 후속 구현이다.
공통 UNKNOWN 최초 시각·나이 메트릭은 구현됐지만 티켓팅의 불명확 5분 운영 확인 등록·중복 없는 알림은 후속이며 좌석 보호 정책을 바꾸지 않는다.
