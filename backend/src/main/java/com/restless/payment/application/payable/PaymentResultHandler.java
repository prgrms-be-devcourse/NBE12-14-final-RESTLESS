package com.restless.payment.application.payable;

import com.restless.payment.domain.PayableType;

import java.util.Optional;

/**
 * 결제 대상 도메인(드롭, 티켓팅, 팬미팅)이 구현하는 결제 결과 수신 계약. 결제 모듈이 정의하고 호출한다 (ADR-0003).
 *
 * <p>결제 모듈은 결제 상태를 바꾸는 <b>같은 트랜잭션 안에서</b> 이 메서드들을 동기 호출한다.
 * 그래서 결제 상태와 도메인 상태(예약, 재고, 주문)는 함께 커밋되거나 함께 롤백된다.
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
     * 승인을 시작해도 되는지 확인하고, 되면 자원을 보호 상태로 바꾼다 (예: 예약 RESERVED → CONFIRMING).
     *
     * @return 예약 만료, 기한 초과 등으로 시작할 수 없으면 reject. 결제 모듈은 PG를 호출하지 않는다.
     */
    ApprovalDecision onApprovalStarting(Long payableId);

    /**
     * PG 승인이 확인됨 (드롭 TX3). 정상 응답과 대사 스케줄러의 재조회 확인 모두 이 메서드로 온다.
     * 구매를 확정한다 (예: 예약 CONFIRMED, 재고 reserved -1 sold +1, 주문 PAID).
     *
     * @return 승인을 받아들일 수 없으면 reject (예: 팬미팅 결제 기한 뒤 승인). 결제 모듈이 보상 취소를 진행한다.
     */
    ApprovalDecision onApproved(Long payableId, ApprovedPayment payment);

    /**
     * PG 미승인이 확정됨 (드롭 TX4). 확보한 자원을 반환한다 (예: 예약 RELEASED, 재고 반환, 주문 CANCELLED).
     * 결제 결과가 불명확한 경우에는 호출되지 않는다.
     */
    void onFailed(Long payableId);

    /**
     * PG 결과가 불명확해짐 (응답 유실, 타임아웃). 자원을 반환하지 말고 보호 상태로 둔다
     * (예: 예약 RECONCILING, 주문 PAYMENT_REVIEW). 최종 결과는 나중에 onApproved 또는 onFailed로 온다 (INV-10).
     */
    void onUnknown(Long payableId);

    /**
     * PG 전액 취소가 확인됨 (드롭 TX5). 판매를 되돌린다 (예: 예약 RESTORED, 재고 sold -1 available +1, 주문 REFUNDED).
     * 정확히 한 번만 반영되어야 한다 (INV-12). 보상 취소(승인 후 reject)의 완료도 이 메서드로 온다.
     */
    void onRefunded(Long payableId);
}
