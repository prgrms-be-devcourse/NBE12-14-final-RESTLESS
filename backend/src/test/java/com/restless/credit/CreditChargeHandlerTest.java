package com.restless.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.RefundContext;
import com.restless.payment.domain.PaymentRefundKind;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 크레딧 충전 결제 결과 처리기 (#316, 설계 6절). 결제 모듈이 걸러 주어 API로는 만들기 어려운 경우
 * (기한 경계, 같은 승인 두 번, 다른 결제의 승인, 동시 확정)는 처리기를 직접 부른다.
 * 설계의 C-03, C-08, C-15와 처리기 계약.
 */
@DisplayName("크레딧 충전 결제 결과 처리기")
class CreditChargeHandlerTest extends CreditChargeTestSupport {

    /** 결제 모듈 없이 처리기를 부를 때 쓰는 가짜 결제 ID. 실제 payments.id와 겹치지 않게 크게 잡는다. */
    static final AtomicLong FAKE_PAYMENT_IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    static ApprovedPayment approved(Long paymentId, long amount, Instant approvedAt) {
        return new ApprovedPayment(paymentId, "pg-" + paymentId, "pk-" + paymentId, amount,
                approvedAt.minusSeconds(1), approvedAt);
    }

    Instant expiresAt(Long chargeId) {
        return ((LocalDateTime) jdbc.queryForObject(
                "SELECT expires_at FROM credit_charges WHERE credit_charge_id = ?", LocalDateTime.class, chargeId))
                .toInstant(ZoneOffset.UTC);
    }

    // ---- load ----

    @Test
    @DisplayName("처리기 종류는 CREDIT_CHARGE")
    void 종류는_CREDIT_CHARGE() {
        assertThat(handler().type()).isEqualTo(creditChargeType());
    }

    @Test
    @DisplayName("READY 충전 주문은 소유자·금액·주문명·결제 기한을 준다")
    void READY는_결제_정보를_준다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 50_000);

        Optional<PayableSnapshot> snapshot = inTx(() -> handler().load(chargeId));

        assertThat(snapshot).hasValueSatisfying(s -> {
            assertThat(s.ownerUserId()).isEqualTo(user);
            assertThat(s.amount()).isEqualTo(50_000);
            assertThat(s.orderName()).isEqualTo("크레딧 50,000");
            assertThat(s.expiresAt()).isCloseTo(expiresAt(chargeId), org.assertj.core.api.Assertions.within(1,
                    ChronoUnit.MICROS));
        });
    }

    @Test
    @DisplayName("PAID이거나 없는 충전 주문은 결제 대상이 아니다 (empty)")
    void PAID나_없는_주문은_empty() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);

        assertThat(inTx(() -> handler().load(chargeId))).isEmpty();
        assertThat(inTx(() -> handler().load(Long.MAX_VALUE))).isEmpty();
    }

    // ---- 승인 시작 ----

    @Test
    @DisplayName("승인 시작: READY이고 기한 안이면 받는다. 상태는 바꾸지 않는다")
    void 기한_안의_승인_시작은_받는다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Long attempt = FAKE_PAYMENT_IDS.incrementAndGet();

        ApprovalDecision decision = inTx(() -> handler().onApprovalStarting(chargeId,
                new PaymentAttemptContext(attempt, Instant.now())));

        assertThat(decision.accepted()).isTrue();
        assertNotGranted(user, chargeId);
    }

    @Test
    @DisplayName("승인 시작: 기한이 지났으면 거절한다")
    void 기한_뒤_승인_시작은_거절한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        setChargeExpiresAt(chargeId, Instant.now().minusSeconds(1));

        ApprovalDecision decision = inTx(() -> handler().onApprovalStarting(chargeId,
                new PaymentAttemptContext(FAKE_PAYMENT_IDS.incrementAndGet(), Instant.now())));

        assertThat(decision.rejected()).isTrue();
        assertNotGranted(user, chargeId);
    }

    @Test
    @DisplayName("승인 시작: 이미 PAID면 거절한다")
    void PAID의_승인_시작은_거절한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);

        ApprovalDecision decision = inTx(() -> handler().onApprovalStarting(chargeId,
                new PaymentAttemptContext(FAKE_PAYMENT_IDS.incrementAndGet(), Instant.now())));

        assertThat(decision.rejected()).isTrue();
        assertGrantedOnce(user, chargeId, 10_000);
    }

    // ---- 승인 확인 ----

    @Test
    @DisplayName("C-08 승인 시작은 기한 안, 승인 확인은 기한 뒤여도 지급한다 (돈을 받고 거절하지 않는다)")
    void 기한_뒤_승인도_지급한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Long attempt = FAKE_PAYMENT_IDS.incrementAndGet();
        inTx(() -> handler().onApprovalStarting(chargeId, new PaymentAttemptContext(attempt, Instant.now())));
        setChargeExpiresAt(chargeId, Instant.now().minusSeconds(60));

        ApprovalDecision decision = inTx(() -> handler().onApproved(chargeId,
                approved(attempt, 10_000, Instant.now())));

        assertThat(decision.accepted()).isTrue();
        assertGrantedOnce(user, chargeId, 10_000);
        assertThat(paidPaymentId(chargeId)).isEqualTo(attempt);
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("같은 결제의 승인이 두 번 오면 둘 다 받지만 지급은 한 번")
    void 같은_승인_두_번은_한_번만_지급한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        ApprovedPayment payment = approved(FAKE_PAYMENT_IDS.incrementAndGet(), 10_000, Instant.now());

        ApprovalDecision first = inTx(() -> handler().onApproved(chargeId, payment));
        Object paidAt = chargeRow(chargeId).get("paid_at");
        ApprovalDecision second = inTx(() -> handler().onApproved(chargeId, payment));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertGrantedOnce(user, chargeId, 10_000);
        assertThat(chargeRow(chargeId).get("paid_at")).isEqualTo(paidAt);
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("이미 PAID인 충전 주문에 다른 결제의 승인이 오면 거절(CHARGE_ALREADY_PAID)해 결제 모듈이 보상 취소하게 한다")
    void 다른_결제의_승인은_거절한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Long firstPayment = FAKE_PAYMENT_IDS.incrementAndGet();
        inTx(() -> handler().onApproved(chargeId, approved(firstPayment, 10_000, Instant.now())));

        ApprovalDecision decision = inTx(() -> handler().onApproved(chargeId,
                approved(FAKE_PAYMENT_IDS.incrementAndGet(), 10_000, Instant.now())));

        assertThat(decision.rejected()).isTrue();
        assertThat(decision.reason()).isEqualTo("CHARGE_ALREADY_PAID");
        assertThat(paidPaymentId(chargeId)).isEqualTo(firstPayment);
        assertGrantedOnce(user, chargeId, 10_000);
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("결제 실패·결과 불명확 통지는 아무것도 바꾸지 않는다")
    void 실패와_불명확은_지급하지_않는다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        PaymentAttemptContext attempt = new PaymentAttemptContext(FAKE_PAYMENT_IDS.incrementAndGet(), Instant.now());

        inTx(() -> handler().onUnknown(chargeId, attempt));
        inTx(() -> handler().onFailed(chargeId, attempt));

        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isZero();
        f.assertInvariants();
    }

    @Test
    @DisplayName("지급은 결제 모듈의 트랜잭션 안에서만 한다. 트랜잭션 밖에서 부르면 예외, 아무것도 바뀌지 않는다")
    void 트랜잭션_밖에서는_지급하지_않는다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);

        assertThatThrownBy(() -> handler().onApproved(chargeId,
                approved(FAKE_PAYMENT_IDS.incrementAndGet(), 10_000, Instant.now())));

        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isZero();
    }

    // ---- 환불 ----

    @Test
    @DisplayName("손님 환불 요청은 기본 거절 REFUND_NOT_SUPPORTED")
    void 손님_환불은_거절한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);

        ApprovalDecision decision = inTx(() -> handler().onRefundRequesting(chargeId,
                new RefundContext(paidPaymentId(chargeId), PaymentRefundKind.CUSTOMER_REFUND)));

        assertThat(decision.rejected()).isTrue();
        assertThat(decision.reason()).isEqualTo("REFUND_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("보상 취소 완료 통지(두 번째 결제 환불)는 충전 주문과 지갑을 바꾸지 않고 예외도 던지지 않는다")
    void 보상_취소_통지는_지갑을_바꾸지_않는다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);
        Long other = FAKE_PAYMENT_IDS.incrementAndGet();

        inTx(() -> handler().onRefunded(chargeId, new RefundContext(other, PaymentRefundKind.COMPENSATION)));

        assertGrantedOnce(user, chargeId, 10_000);
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }

    // ---- 동시성 ----

    @Test
    @DisplayName("C-03 같은 결제의 승인 확정이 두 트랜잭션에서 동시에 와도 둘 다 받고 지급은 한 번")
    void 동시_승인_확정은_한_번만_지급한다() throws Exception {
        Long user = f.user("USER");
        for (int round = 0; round < 5; round++) {
            Long chargeId = newCharge(user, 10_000);
            ApprovedPayment payment = approved(FAKE_PAYMENT_IDS.incrementAndGet(), 10_000, Instant.now());

            List<ApprovalDecision> decisions = concurrently(
                    () -> inTx(() -> handler().onApproved(chargeId, payment)),
                    () -> inTx(() -> handler().onApproved(chargeId, payment)));

            assertThat(decisions).as("round %d", round).allMatch(ApprovalDecision::accepted);
            assertGrantedOnce(user, chargeId, 10_000);
        }
        assertThat(balance(user)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("C-15 충전 승인과 같은 회원의 입찰이 동시에 와도 데드락 없이 둘 다 끝나고 불변식이 지켜진다")
    void 충전_승인과_입찰이_동시에_와도_끝난다() throws Exception {
        Long seller = f.user("ORGANIZER");
        Long user = f.user("USER");
        f.credit(user, 1_000_000);
        for (int round = 0; round < 5; round++) {
            Long auctionId = f.openAuction(seller);
            Long chargeId = newCharge(user, 10_000);
            ApprovedPayment payment = approved(FAKE_PAYMENT_IDS.incrementAndGet(), 10_000, Instant.now());

            List<Object> results = concurrently(
                    () -> inTx(() -> handler().onApproved(chargeId, payment)),
                    () -> mockMvc.perform(post("/api/auctions/" + auctionId + "/bids")
                                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"amount\":50000}"))
                            .andReturn().getResponse().getStatus());

            assertThat(((ApprovalDecision) results.get(0)).accepted()).as("round %d", round).isTrue();
            assertThat(results.get(1)).as("round %d bid", round).isEqualTo(201);
            assertGrantedOnce(user, chargeId, 10_000);
        }
        assertThat(balance(user)).isEqualTo(1_050_000);
        f.assertInvariants();
    }

    /** 두 작업을 같은 순간에 시작해 결과를 순서대로 돌려준다. 10초 안에 끝나지 않으면 실패한다. */
    @SafeVarargs
    final <T> List<T> concurrently(Callable<? extends T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<? extends T>> futures = new ArrayList<>();
            for (Callable<? extends T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<? extends T> future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
