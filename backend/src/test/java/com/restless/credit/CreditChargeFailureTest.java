package com.restless.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.restless.credit.domain.CreditTransaction;
import com.restless.credit.infrastructure.CreditTransactionRepository;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * C-14 지급 중 기술 오류 (#316, 설계 6절 마지막 문단). 충전 내역 저장에서 DB 오류가 나면 지갑·충전 주문이 함께
 * 롤백되고, 예외가 결제 모듈로 올라가 결제가 PROCESSING/UNKNOWN으로 남는다. 다시 확정하면 지급은 한 번이다.
 * 스파이 빈 때문에 Spring 컨텍스트를 따로 띄우므로 다른 테스트와 섞지 않는다.
 */
@DisplayName("크레딧 충전 지급 실패")
class CreditChargeFailureTest extends CreditChargeTestSupport {

    @MockitoSpyBean CreditTransactionRepository transactions;

    @AfterEach
    void resetSpy() {
        reset(transactions);
    }

    void failChargeLedger() {
        var error = new DataAccessResourceFailureException("injected: credit_transactions insert failed");
        doThrow(error).when(transactions).save(argThat(CreditChargeFailureTest::isCharge));
        doThrow(error).when(transactions).saveAndFlush(argThat(CreditChargeFailureTest::isCharge));
    }

    static boolean isCharge(CreditTransaction t) {
        return t != null && t.getTransactionType() != null && "CHARGE".equals(t.getTransactionType().name());
    }

    @Test
    @DisplayName("처리기: 내역 저장이 실패하면 예외를 던지고 지갑·충전 주문은 그대로. 다시 부르면 한 번 지급")
    void 내역_저장이_실패하면_모두_롤백된다() throws Exception {
        Long user = f.user("USER");
        f.credit(user, 5_000);
        Long chargeId = newCharge(user, 10_000);
        Long paymentId = CreditChargeHandlerTest.FAKE_PAYMENT_IDS.incrementAndGet();
        ApprovedPayment payment = CreditChargeHandlerTest.approved(paymentId, 10_000, Instant.now());
        failChargeLedger();

        assertThatThrownBy(() -> inTx(() -> handler().onApproved(chargeId, payment)));

        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isEqualTo(5_000);
        f.assertInvariants();

        reset(transactions);
        ApprovalDecision retry = inTx(() -> handler().onApproved(chargeId, payment));

        assertThat(retry.accepted()).isTrue();
        assertGrantedOnce(user, chargeId, 10_000);
        assertThat(balance(user)).isEqualTo(15_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("API: 승인 뒤 지급이 실패하면 202, 결제는 확정되지 않고 지급 없음. 재확인 작업이 다시 확정하면 한 번 지급")
    void 승인_뒤_지급_실패는_재확인_작업이_복구한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Prepared p = prepareOk(user, chargeId);
        fakePg.willApprove(p.pgOrderId());
        failChargeLedger();

        var res = confirm(user, p);

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(202);
        assertThat(paymentStatus(p.paymentId())).isIn("PROCESSING", "UNKNOWN");
        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isZero();

        reset(transactions);
        ageForReconcile(p.paymentId());
        reconciler.runOnce();

        assertThat(paymentStatus(p.paymentId())).isEqualTo("APPROVED");
        assertGrantedOnce(user, chargeId, 10_000);
        assertPaidBy(chargeId, p.paymentId());
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }
}
