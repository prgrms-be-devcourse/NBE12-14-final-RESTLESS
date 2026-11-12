# 티켓팅 PaymentResultHandler 연결

- 상태: 상태 전이는 DECIDED (2026-11-12, 담당자 합의).
- 기준: ADR-0003의 동기 Handler 계약. 코드는 아직 구현하지 않았다.
- paymentId를 모든 상태 변경 Handler에 전달하는 원칙은 DECIDED (2026-11-12). DTO·저장 구조와 공유 인터페이스 변경은 공통 담당자와 조율한다. 결제 대상 매핑은 제안 상태다.

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

## 현재 공통 계약과의 차이

- onApprovalStarting/onFailed/onUnknown은 payableId만 받아 시도 식별·기록 시각을 전달하지 못한다.
- onApproved의 ApprovedPayment에는 paymentId가 있지만 승인 시도 기록 시각은 없다. approvedAt의 현재 주석은 PG 시각을 기한 기준으로 설명하며 티켓팅 정책과 다르다.
- onFailed의 자원 반환 설명은 티켓팅에 항상 적용되지 않는다. 기한 내 재시도 대기가 필요하다.
- 과거 결과가 새 시도에 영향을 주지 않도록 시도 식별 전달 또는 결제 모듈의 차단 계약이 필요하다. paymentId 전달 원칙은 확정했으며 공유 코드의 구체적인 변경은 공통 담당자와 조율한다.

## 검증 계획 (미실행)

- 승인 시작 시 예약 상태 유지, 기한/소유권 불일치 REJECT, PG 호출 금지.
- 기한 내 유효 시도의 늦은 성공 확정, 티켓/카운터 한 번만 반영.
- 최종 실패의 기한 전/정각/이후 분기와 재시도 가능 상태.
- 불명확 결과 중복 처리: 최초 시각 유지, 자원 반환 없음.
- 이전 시도의 늦은 실패·불명확 결과가 새 시도·확정 예약을 변경하지 않음.
- 과거 시도의 상충하는 성공은 단순 무시하지 않고 공통 결제에 조사·보상 대상으로 남김.

## 승인 시도 식별 (DECIDED, 2026-11-12)

- payments.id를 승인 시도 ID로 사용하며 별도 시도 UUID는 추가하지 않는다.
- onApprovalStarting/onApproved/onFailed/onUnknown/onRefunded 모두 해당 paymentId를 전달한다.
- type과 load는 상태 변경 Handler가 아니다. load는 결제 생성 전 대상 조회이므로 paymentId를 요구하지 않는다.
- 같은 시도의 결과 조회·복구는 같은 ID를 사용하고, 최종 실패 후 새 시도는 새 payments 행과 ID를 사용한다.
- 결제 모듈은 잠근 paymentId와 payableType/payableId 관계를 확인한다. 정상 응답·재조회·운영 복구 모두 같은 ID를 전달한다.
- 티켓팅은 현재 시도와 예약 상태를 함께 확인한다. 이전 시도 실패·불명확 결과로 새 시도를 변경하지 않는다.
- 이전 시도의 상충하는 성공은 단순 무시하지 않고 조사·보상 대상으로 남긴다.

## 공통 담당자에게 요청할 계약 초안 (PROPOSED)

기존 ApprovedPayment의 paymentId는 유지하고, 나머지 상태 변경 메서드에 시도 문맥을 추가한다.

```java
record PaymentAttemptContext(Long paymentId, Instant approvalStartedAt) {}

ApprovalDecision onApprovalStarting(Long payableId, PaymentAttemptContext attempt);
ApprovalDecision onApproved(Long payableId, ApprovedPayment payment);
void onFailed(Long payableId, PaymentAttemptContext attempt);
void onUnknown(Long payableId, PaymentAttemptContext attempt);
void onRefunded(Long payableId, PaymentAttemptContext attempt);
```

- approvalStartedAt은 유효 승인 준비를 기록한 서버 시각으로 영속화한다. 생성 시각·PG 승인 시각과 다르다.
- 필요한 도메인 잠금까지 얻은 뒤 기한을 판정해야 한다. 잠금 대기 전 만든 시각을 그대로 사용하지 않는다. 시각 확정·전달 순서는 공동 설계 대상이다.
- 승인 전 READY 만료 실패에는 approvalStartedAt이 없을 수 있다. null 허용 또는 별도 문맥으로 표현하되 paymentId는 필수다.
- ApprovedPayment에는 approvalStartedAt을 추가하거나 예약에 저장한 시각으로 검증한다. 현재 approvedAt을 공통 기한 기준으로 설명하는 주석은 도메인별 기준에 맞춰 수정한다.
- 예약에 current_payment_id와 승인 시작 기록 시각 저장을 제안한다. 확정 후에도 성공한 시도를 식별할 수 있어야 한다. 실제 컬럼·FK는 후속 설계 대상이다.
- onFailed는 무조건 자원 반환이 아니라 도메인 정책에 따른 조건부 반환으로 문서화한다.
- onRefunded에서 정상 환불과 승인 거절 보상 취소를 구분한다. 예매 확정 전 보상 취소에 confirmed_count 감소를 적용하지 않는다. 추가 문맥은 후속 합의 대상이다.
- 이번 변경은 docs만 반영한다. 공유 인터페이스·도메인 코드·마이그레이션은 수정하지 않는다.
