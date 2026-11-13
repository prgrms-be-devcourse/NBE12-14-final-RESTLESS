# 장애 처리 설계

테스트 계획과 별도로, 장애마다 아래 항목을 정의한다. 아직 채우지 않은 항목은 TBD로 둔다. 아래 표의 "테스트"는 그 장애를 재현해 검증하는 테스트다. 작성 틀의 상세 항목은 아직 채우지 않았다.

## 작성 틀

| 항목 | 내용 |
|---|---|
| 장애 지점 | |
| 관측 가능한 증상 | |
| 유지해야 할 상태 | |
| 재시도 가능 여부 | |
| 복구 주체 | |
| 최종 상태 | |
| 보장할 불변식 | |
| 검증 테스트 | |

## 대상 장애 (Drop)

| ID | 장애 | 상태 |
|---|---|---|
| F-01 | 재고 예약 후 Order 생성 실패 | 테스트: `DropReservationIntegrationTest.inv01AndInv07OrderFailureRollsBackInventorySlotAndOrderThenSameKeyCanRetry` (전체 롤백) |
| F-02 | MySQL Deadlock, Lock Timeout | 일부: 결제 준비·승인 시작 잠금 순환은 `PaymentFlowTest.prepareAndApprovalStartOverlapWithoutDeadlockOr500` (#73). 일반 Lock Timeout은 TBD |
| F-03 | PG 승인 거절 | 테스트: `DropPaymentAcceptanceTest.failureBeforeDeadlineKeepsHoldForRetry`, `failureAfterDeadlineReleasesStock`, `PaymentFlowTest.pgRejectedCommitsFailureAndAttemptContext` |
| F-04 | PG 승인 타임아웃 | 테스트: `PaymentFlowTest.inv10_timeoutAndLostResponseRemainUnknown`, `DropPaymentAcceptanceTest.unknownResultProtectsStockUntilReconciled` |
| F-05 | PG 승인 성공 후 응답 유실 | 테스트: `PaymentReconciliationTest.lostResponseRecoversActualApprovalWithoutApprovingAgain` |
| F-06 | PG 승인 직후 서버 종료 | 대사가 PROCESSING을 재조회한다. 테스트: `PaymentReconciliationTest.staleProcessingApprovedUsesStoredAttemptOnce` |
| F-07 | PG 승인 후 DB 구매 확정 실패 | 테스트: `PaymentFlowTest.technicalFailureRollsBackResultAndReturnsProcessing`, `PaymentReconciliationTest.handlerExceptionRollsBackAndDoesNotBlockNextPayment` |
| F-08 | 중복 승인 결과 처리 | 테스트: `DropPaymentAcceptanceTest.duplicateConfirmationsApplyOnce`, `PaymentReconciliationTest.reconciliationRacesUserConfirmButApprovesOnce` |
| F-09 | PG 환불 취소 결과 불명확 | 테스트: `PaymentReconciliationTest.cancelUnknownCompletesWithStoredKind`, `cancellationUnknownCanBeRetriedRepeatedlyWithoutInvalidSelfTransition` |
| F-10 | PG 취소 성공 후 서버 종료 | 대사가 같은 키로 취소를 다시 보낸다. 테스트: `PaymentReconciliationTest.staleCancelPendingRetriesSameKeyOutsideTx` |
| F-11 | 예약 만료 Worker 중복 실행 | 테스트: `DropPaymentLifecycleIntegrationTest.twoExpiryWorkersReleaseOnceAndDisabledSchedulerLeavesRunnableJob`, `DropPaymentAcceptanceTest.approvalStartRacesExpiryWithOneOutcome` |
| F-12 | 구매 대기열 저장소 장애 | TBD |
| F-13 | 구매 확정 커밋 후 리스너 실행 전 서버 종료 (이벤트 유실) | [ADR-0001](../decisions/0001-event-handling.md) 관측 방법 참고 |

## 공통 결제 대사 (#46)

결제 모듈이 미완료 결제를 조회하고 [ADR-0003](../decisions/0003-payment-module-boundary.md)의 동기 Handler로 도메인을 함께 확정한다.
승인은 **재요청하지 않고 조회만** 한다. PG 호출 전후 DB 트랜잭션을 분리하며 결과 반영 시 결제 행 → 환불 행 → Handler의 도메인 행 순으로 잠근다.
후보를 읽을 때의 상태와 잠근 뒤의 상태가 다르면 그 결과는 반영하지 않는다. Handler 예외는 전체 반영을 롤백하고 다음 실행에서 다시 조회한다.

| 대상 | PG 조회 결과 | 처리 |
|---|---|---|
| 오래된 PROCESSING / UNKNOWN | Approved, 금액 일치 | 공통 TX-B: APPROVED + `onApproved`. Handler reject면 COMPENSATION 환불 생성 후 트랜잭션 밖 취소, 공통 TX-C로 반영 |
| 동일 | Approved, 금액 불일치 | UNKNOWN 보호 상태 유지/진입, `payment.amount.mismatch`. PROCESSING에서 처음 진입할 때만 `onUnknown` |
| 동일 | NotApproved (최종 미승인) | FAILED + `onFailed`, 저장된 `approval_started_at`으로 시도 문맥 전달 |
| 동일 | InProgress | 상태 유지 |
| 동일 | Unknown | UNKNOWN 유지. PROCESSING이면 UNKNOWN + `onUnknown` |
| 동일 | NotFound, 승인 시작 후 15분 미만 | UNKNOWN 유지. PROCESSING이면 UNKNOWN + `onUnknown` |
| 동일 | NotFound, 승인 시작 후 15분 이상 | FAILED(`PG_NOT_FOUND`) + `onFailed` |
| 동일 | Cancelled | 외부 취소 이상으로 UNKNOWN 유지/진입, 오류 로그 + `payment.reconcile.anomaly{reason=unexpected_cancelled}`. 자동 환불 확정 안 함 |
| READY, `expires_at < now` | PG 호출 없음 | 잠금 후 여전히 READY이고 기한이 지났으면 FAILED(`EXPIRED`) + `onFailed(notStarted)`. 활성 시도 UNIQUE 해제. 기한 NULL은 제외 |
| 오래된 CANCEL_PENDING / CANCEL_UNKNOWN | Cancelled | 저장된 환불 REQUESTED/UNKNOWN → COMPLETED, 결제 CANCELLED, `onRefunded(paymentId, refund.kind)` |
| 동일 | Approved | 동일 환불의 저장된 멱등성 키로 취소 재시도. TX-C: Cancelled면 완료, Rejected면 환불 FAILED·결제 APPROVED, Unknown이면 CANCEL_UNKNOWN 유지/진입 |
| 동일 | InProgress / Unknown / NotFound / NotApproved | 취소 결과 미확정. 상태 유지 |
| 동일, 진행 중 환불 없음 | PG 호출 없음 | 상태 유지, 오류 로그 + `payment.reconcile.anomaly{reason=missing_refund}` |

### NotFound 최종 실패 기준

[토스페이먼츠 FAQ](https://docs.tosspayments.com/resources/faq)는 결제 요청 완료 후 10분 안에 승인해야 한다고 설명한다.
이 서비스는 그보다 긴 **승인 시작 기록 후 15분**을 기다리고, 그 시점의 PG 조회가 명시적 NotFound일 때만 미승인으로 닫는다.
이는 #46에서 채택한 최종 판정 정책이다. 10분 승인 제한으로부터 PG 조회의 영구 미존재 보장까지를 직접 증명하는 것은 아니며,
승인 처리 지연·조회 가시성에 대한 공급자 보장이 달라지면 이 정책도 재검토해야 한다.
통신 오류, InProgress, Unknown 또는 취소 경로의 NotFound에는 이 시간 기준을 적용하지 않는다.

### 설정과 후보 조회

`restless.payment.reconcile` 아래 설정이다. 테스트는 Gradle systemProperty로 enabled=false를 주고 `runOnce()`를 직접 실행한다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| enabled | `${PAYMENT_RECONCILE_ENABLED:true}` | 자동 스케줄러 및 결제 scheduling config 활성화 |
| fixed-delay-ms | 5000 | 한 실행 완료 후 다음 실행까지 간격 |
| batch-size | 100 | 상태별 한 번의 후보 조회 상한 (합계 최대 5개 상태 × 100건) |
| processing-stale-after | 30s | PROCESSING의 `updated_at` 경과 기준. Toss read timeout 30초 고려 |
| unknown-retry-after | 10s | UNKNOWN 및 CANCEL_UNKNOWN의 `updated_at` 경과 기준 |
| cancel-stale-after | 30s | CANCEL_PENDING의 `updated_at` 경과 기준 |
| not-found-final-after | 15m | `approval_started_at` 기준 NotFound 최종 판정 대기 |

각 상태별로 오래된 행부터 ID 순서를 보조 정렬해 조회한다. PROCESSING/UNKNOWN/취소는 기존 `(status, updated_at)` 인덱스를,
READY 만료는 새 `(status, expires_at)` 인덱스를 쓴다. 미확정 조회 후 `updated_at`을 갱신해 최소 재조회 간격을 지킨다.
`unknown_since_at`은 최초 UNKNOWN 진입 때 한 번만 기록해 반복 조회와 서버 재시작에도 UNKNOWN 나이를 보존한다.
마이그레이션 이전 UNKNOWN은 기존 `updated_at`으로 보수적으로 복원하며 그 이전 최초 진입 시각은 복원할 수 없다.

별도 리스는 없다. 서버들이 동일 결제를 중복 조회할 수 있고 취소도 동일 멱등성 키로 중복 요청할 수 있다.
DB 결제 잠금과 상태 재확인으로 최종 Handler 반영은 한 번만 한다. 비용은 서버 수에 따른 PG 조회/멱등 취소 요청 증가다.
한 건의 예외는 로그와 `payment.reconcile.failure{stage}`에 기록하고 나머지 건은 계속 처리한다.

### 관측과 운영 한계

- `payment.reconcile.processed{target=processing|unknown|ready_expired|cancel,result=...}`: 건별 처리 결과.
- `payment.reconcile.run`: 후보 조회부터 후속 취소까지 실행 시간 Timer.
- `payment.unknown.count`, `payment.unknown.oldest.age.seconds`: 마지막 실행 시 집계한 UNKNOWN 수와 최초 UNKNOWN 진입부터의 최대 나이. 나이는 현재 Clock으로 계산하며 음수는 0으로 제한한다.
- `payment.reconcile.cancel.retry`: 실제 취소 재시도 수 (태그 없음). 환불별로 시계열이 늘지 않게 ID는 태그 대신 로그(`paymentId`, `refundId`)로 남긴다.
- `payment.reconcile.anomaly{reason}`, `payment.amount.mismatch`: 운영 확인이 필요한 데이터/PG 불일치.

티켓팅의 [불명확 5분 운영 확인](../domains/ticketing/transactions.md#9-불명확-결과의-운영-확인-전환-decided-2026-11-12)을 위해
UNKNOWN 나이를 제공한다. **운영 확인 등록 여부 저장, 중복 없는 알림 및 실제 알림 채널은 이번 구현에 포함하지 않는다.**
운영 확인이 필요해도 결제와 자원을 시간만으로 해제하지 않으며 대사를 계속한다.

## PG 설정

| 설정 | 기본값 | 내용 |
|---|---|---|
| `pg.provider` | 없으면 `toss`. dev 프로필은 `${PG_PROVIDER:fake}` | `fake`면 실제 PG를 부르지 않는 Fake PG를 쓴다. 부하 테스트는 반드시 `fake` |
| `toss.secret-key` (환경 변수 `TOSS_SECRET_KEY`) | 없음 | `toss`일 때 필수. 비어 있으면 기동이 실패한다 (모든 결제가 401로 UNKNOWN에 쌓이는 것을 막음). 서버에서만 읽는다 |
| `toss.base-url` | `https://api.tosspayments.com` | 결제 승인·조회·취소 호출 대상 |
| `toss.connect-timeout`, `toss.read-timeout` | 3초, 30초 | 읽기 타임아웃이면 결과를 UNKNOWN으로 두고 대사가 확인한다 |
| `pg.fake.*` | | Fake PG 응답 설정 (테스트·부하 테스트용) |
