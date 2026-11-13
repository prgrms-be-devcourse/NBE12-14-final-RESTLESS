package com.restless.payment.application.payable;

import com.restless.payment.domain.PayableType;

import java.util.Optional;

/**
 * 결제 대상 도메인(드롭, 티켓팅, 팬미팅)이 구현하는 결제 결과 수신 계약. 결제 모듈이 정의하고 호출한다 (ADR-0003).
 *
 * <p>결제 모듈은 결제 상태를 바꾸는 <b>같은 트랜잭션 안에서</b> 이 메서드들을 동기 호출한다.
 * 그래서 결제 상태와 도메인 상태(예약, 재고, 주문)는 함께 커밋되거나 함께 롤백된다.
 *
 * <p><b>격리 수준:</b> 결제 모듈은 Handler를 부르는 트랜잭션을 {@code READ_COMMITTED}로 연다
 * ({@code PaymentTransactions}). Handler 안의 도메인 코드도 이 격리 수준으로 실행된다. 같은 행을 다시 읽으면 그 사이 커밋된
 * 값이 보일 수 있으므로, 판정에 쓰는 도메인 행은 먼저 {@code FOR UPDATE}로 잠근 뒤 읽거나 조건부 UPDATE로 바꾼다.
 * 잠그지 않은 조회 결과만으로 상태를 결정하지 않는다.
 *
 * <h2>구현 규칙</h2>
 * <ul>
 *   <li><b>업무상 받을 수 없음</b>(예약 만료, 결제 기한 초과 등)은 {@link ApprovalDecision#reject(String)}로 반환한다.
 *       결제 모듈이 승인 시작을 막거나, 이미 승인된 돈은 보상 취소한다.</li>
 *   <li><b>기술 오류</b>(DB 오류 등)는 예외로 던진다. 전체가 롤백되고 결제는 PROCESSING/UNKNOWN으로 남아 대사가 재시도한다.
 *       업무 거절을 예외로 표현하지 않는다.</li>
 *   <li>PG 호출, 외부 HTTP 호출 등 느린 I/O를 하지 않는다. 트랜잭션과 잠금 시간이 길어진다.</li>
 *   <li>결제 모듈이 결제 행을 먼저 잠근 뒤 호출한다. 구현체는 자기 도메인 행만 잠근다. 결제 행을 다시 잠그지 않는다.</li>
 *   <li>같은 결과가 두 번 와도 한 번만 반영되도록 조건부 상태 전이로 멱등하게 만든다 (INV-11).</li>
 *   <li>상태 변경 메서드는 모두 결제 시도({@code paymentId})를 받는다. 승인 시작을 받아들인 시도가 아닌 결과로는
 *       자원을 바꾸지 않는다. 결제 모듈도 결제 행의 조건부 전이로 같은 시도의 중복 결과를 거르지만, 도메인이 한 번 더 확인한다.</li>
 *   <li>다른 도메인의 Repository를 직접 쓰지 않는다. 공통 주문 변경은 주문 모듈의 application 서비스를 쓴다.</li>
 *   <li>알림 같은 후속 작업은 여기서 하지 않는다. 커밋 후 이벤트(AFTER_COMMIT)로 처리한다 (ADR-0001).</li>
 * </ul>
 *
 * <p>구현체는 Spring 빈으로 등록한다. {@link #type()}마다 구현체는 정확히 하나여야 한다.
 */
public interface PaymentResultHandler {

    /** 이 구현체가 맡는 결제 대상 종류. */
    PayableType type();

    /**
     * 결제를 만들기 전에 결제 대상을 확인한다. 결제 모듈은 반환된 금액을 결제 금액으로 쓰고(INV-08),
     * 소유자가 요청한 회원과 같은지, 기한이 남았는지 확인한다.
     *
     * @return 대상이 없으면 empty. 결제할 수 없는 상태(이미 결제됨, 취소됨 등)도 empty로 반환한다.
     */
    Optional<PayableSnapshot> load(Long payableId);

    /**
     * PG 승인 호출 직전 (드롭 TX2). 결제는 READY → PROCESSING으로 바뀌는 중이다.
     * 승인을 시작해도 되는지 확인하고, 되면 자원을 보호 상태로 바꾸고 이 시도를 현재 시도로 기록한다
     * (예: 예약 RESERVED → CONFIRMING, 예약에 paymentId 기록).
     *
     * <p>기한은 도메인 행을 잠근 뒤 직접 읽은 현재 시각으로 판정한다. {@code attempt.approvalStartedAt()}은 잠금 대기 전에
     * 정해진 값이라 판정에 쓰지 않는다. 잠근 뒤의 시각이 항상 더 늦으므로, 받아들인 시도의 approvalStartedAt은 기한 안이다.
     *
     * @param attempt 시작하려는 시도. approvalStartedAt은 null이 아니다
     * @return 예약 만료, 기한 초과 등으로 시작할 수 없으면 reject. 결제 모듈은 PG를 호출하지 않는다.
     */
    ApprovalDecision onApprovalStarting(Long payableId, PaymentAttemptContext attempt);

    /**
     * PG 승인이 확인됨 (드롭 TX3). 정상 응답과 대사 스케줄러의 재조회 확인 모두 이 메서드로 온다.
     * 구매를 확정한다 (예: 예약 CONFIRMED, 재고 reserved -1 sold +1, 주문 PAID).
     * 기한 판정 기준은 도메인 정책이 정한다 ({@link ApprovedPayment} 참고).
     *
     * @return 승인을 받아들일 수 없으면 reject (예: 팬미팅 결제 기한 뒤 승인, 현재 시도가 아님). 결제 모듈이 보상 취소를 진행한다.
     */
    ApprovalDecision onApproved(Long payableId, ApprovedPayment payment);

    /**
     * 이 시도의 PG 미승인이 확정됨 (드롭 TX4). 승인을 시작하지 않고 기한이 지나 닫힌 시도도 이 메서드로 온다.
     * 결제 결과가 불명확한 경우에는 호출되지 않는다.
     *
     * <p>자원을 반환할지는 도메인 정책이 정한다. 기한이 남았으면 자원을 유지하고 다시 결제할 수 있는 상태로 돌릴 수 있다
     * (예: 드롭 예약 CONFIRMING → RESERVED, 주문 PENDING_PAYMENT). 기한이 지났으면 반환한다
     * (예: 예약 RELEASED, 재고 반환, 주문 CANCELLED). 다시 결제하면 새 시도(새 paymentId)가 생긴다.
     *
     * @param attempt 실패한 시도. 승인을 시작하지 않은 시도면 approvalStartedAt이 null이다
     */
    void onFailed(Long payableId, PaymentAttemptContext attempt);

    /**
     * 이 시도의 PG 결과가 불명확해짐 (응답 유실, 타임아웃). 자원을 반환하지 말고 보호 상태로 둔다
     * (예: 예약 RECONCILING, 주문 PAYMENT_REVIEW). 최종 결과는 나중에 같은 paymentId로 onApproved 또는 onFailed로 온다 (INV-10).
     * 불명확한 시도가 있는 동안 결제 모듈은 같은 대상의 새 시도를 만들지 않는다.
     *
     * @param attempt 결과가 불명확한 시도. approvalStartedAt은 null이 아니다
     */
    void onUnknown(Long payableId, PaymentAttemptContext attempt);

    /**
     * 손님 전액 환불을 시작하기 직전, TX-R1에서 결제 행을 잠근 뒤 호출한다.
     * 결제는 아직 APPROVED이고 환불 요청 행은 아직 생성되지 않았다.
     * 받아들이면 도메인 행을 잠그고 환불 대기로 바꾼다 (예: 주문 PAID → REFUND_PENDING).
     * 이 변경과 결제 CANCEL_PENDING·환불 REQUESTED 저장은 함께 커밋된다.
     *
     * <p>기본 구현은 REFUND_NOT_SUPPORTED로 거절한다. 보상 취소에는 호출하지 않는다.
     * 재고·좌석은 반환하지 않으며 실제 PG 취소 확정 후 onRefunded에서만 복원한다.
     * PG 호출이나 외부 I/O를 하지 않는다. 기술 오류는 예외로 던져 전체 요청을 롤백한다.
     *
     * @param refund 결제 ID와 CUSTOMER_REFUND 종류를 담은 문맥
     * @return accept면 요청을 진행한다. reject면 reason을 응답하고 트랜잭션 전체를 롤백해
     *         결제/도메인 상태를 유지하며 PG를 호출하지 않는다.
     */
    default ApprovalDecision onRefundRequesting(Long payableId, RefundContext refund) {
        return ApprovalDecision.reject("REFUND_NOT_SUPPORTED");
    }

    /**
     * PG가 손님 환불 취소를 최종 거절했을 때, 공통 TX-C/TX-R2에서 호출한다.
     * 결제 행 → 환불 행 잠금과 상태 재확인 뒤 환불 FAILED·결제 APPROVED 복귀와 같은
     * 트랜잭션 안에서 실행된다. 대사 취소 재시도의 최종 거절도 같은 경로를 쓴다.
     * 도메인의 환불 대기는 운영 확인 상태로 바꾼다 (예: REFUND_PENDING → REFUND_REVIEW).
     *
     * <p>기본 구현은 아무것도 하지 않는다. CUSTOMER_REFUND에만 호출하고 COMPENSATION에는
     * 호출하지 않는다. 재고·좌석은 복원하지 않는다. PG/외부 I/O를 하지 않으며 기술 오류는
     * 예외로 던진다. 예외가 나면 결제·환불·도메인 변경이 함께 롤백되고 대사가 재확인한다.
     *
     * @param refund 저장된 결제 ID와 CUSTOMER_REFUND 종류
     */
    default void onRefundRejected(Long payableId, RefundContext refund) {
    }

    /**
     * PG 전액 취소가 확인됨 (드롭 TX5). 정확히 한 번만 반영되어야 한다 (INV-12). 종류에 따라 되돌릴 자원이 다르다.
     * <ul>
     *   <li>{@code CUSTOMER_REFUND}: 확정된 구매를 되돌린다 (예: 예약 RESTORED, 재고 sold -1 available +1, 주문 REFUNDED).</li>
     *   <li>{@code COMPENSATION}: onApproved에서 거절한 승인의 보상 취소가 끝났다. 구매는 확정된 적이 없으므로 확정 수량을 줄이지 않는다
     *       (예: 예약 COMPENSATING → RELEASED, 재고 reserved -1 available +1).</li>
     * </ul>
     * 종류와 도메인 상태가 맞지 않으면(예: COMPENSATION인데 예약이 CONFIRMED) 자원을 바꾸지 않고 오류 로그와 메트릭으로 남긴다
     * (운영 확인 대상). 예외를 던지면 PG 취소는 끝났는데 결제 반영이 계속 롤백되므로 던지지 않는다.
     */
    void onRefunded(Long payableId, RefundContext refund);
}
