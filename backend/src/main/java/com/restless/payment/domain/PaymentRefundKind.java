package com.restless.payment.domain;

/**
 * 환불이 왜 일어났는지. 도메인은 종류에 따라 되돌릴 자원이 다르다.
 *
 * <ul>
 *   <li>{@link #CUSTOMER_REFUND}: 확정된 구매를 되돌린다 (예: 드롭 CONFIRMED → RESTORED, sold -1 available +1).</li>
 *   <li>{@link #COMPENSATION}: PG는 승인했지만 도메인이 {@code onApproved}에서 거절해 결제 모듈이 돈을 돌려준다.
 *       구매는 확정된 적이 없다 (예: 드롭 COMPENSATING → RELEASED, reserved -1 available +1).</li>
 * </ul>
 */
public enum PaymentRefundKind {
    CUSTOMER_REFUND, COMPENSATION
}
