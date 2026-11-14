package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.fanmeeting.application.FanMeetingExpiryWorker;
import com.restless.fanmeeting.application.FanMeetingOrderExpiry;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.infrastructure.fake.FakePgClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 Worker와 보류 30분 경고 (`transactions.md` 8절). 스케줄러는 테스트에서 꺼져 있어 워커를 직접 부른다.
 * 만료 Worker는 결제 모듈이 알려 줄 수 없는 주문(결제 대기, 기한 경과, 진행 중인 시도 없음)만 만료한다.
 */
@DisplayName("팬미팅 만료 Worker")
@ExtendWith(OutputCaptureExtension.class)
class FanMeetingExpiryWorkerTest extends FanMeetingOrderApiSupport {

    @Autowired FanMeetingExpiryWorker worker;
    @Autowired FanMeetingOrderExpiry expiry;
    @Autowired FakePgClient fakePg;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    @Test
    @DisplayName("결제를 준비하지 않고 기한이 지난 주문 → 주문·선발 EXPIRED. 기한 전 주문은 그대로")
    void 기한이_지난_결제_대기_주문을_만료한다() throws Exception {
        WinnerOrder late = winnerOrder();
        WinnerOrder inTime = winnerOrder();
        setDeadline(late.orderId(), Instant.now().minusSeconds(1));
        setDeadline(inTime.orderId(), Instant.now().plusSeconds(30));

        worker.expireDue();

        assertThat(orderStatus(late.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(late.orderId())).isEqualTo("EXPIRED");
        assertThat(orderStatus(inTime.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(inTime.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("결제창만 열어 둔 주문(READY 시도)도 기한이 지나면 만료하고, 그 뒤 승인 요청은 받지 않는다")
    void 결제창만_열어_둔_주문도_만료한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "ready-only");
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));

        worker.expireDue();
        fakePg.willApprove(p.pgOrderId());
        MvcResult confirmed = confirm(order.bearer(), p);

        assertThat(confirmed.getResponse().getStatus()).as(contentOf(confirmed)).isEqualTo(409);
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).as("INV-FM-10 만료된 주문에 승인 0건").isNull();
    }

    @Test
    @DisplayName("결제가 실패한 뒤 다시 결제하지 않고 기한이 지난 주문 → EXPIRED")
    void 실패_뒤_다시_결제하지_않은_주문을_만료한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "failed");
        fakePg.willReject(p.pgOrderId(), "REJECT_CARD_COMPANY");
        confirm(order.bearer(), p);
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));

        worker.expireDue();

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("승인이 진행 중인 주문(현재 시도 있음)과 보류 주문은 기한이 지나도 만료하지 않는다 (INV-FM-11)")
    void 승인_진행_중이거나_보류인_주문은_만료하지_않는다() throws Exception {
        WinnerOrder approving = winnerOrder();
        WinnerOrder review = winnerOrder();
        Long approvingAttempt = startApproval(approving.orderId());
        Long reviewAttempt = startApproval(review.orderId());
        inTx(() -> {
            handler().onUnknown(review.orderId(), new PaymentAttemptContext(reviewAttempt, Instant.now()));
            return null;
        });
        setDeadline(approving.orderId(), Instant.now().minusSeconds(1));
        setDeadline(review.orderId(), Instant.now().minusSeconds(1));

        worker.expireDue();

        assertThat(orderStatus(approving.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(orderColumn(approving.orderId(), "payment_id")).isEqualTo(approvingAttempt);
        assertThat(selectionStatus(approving.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(orderStatus(review.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(selectionStatus(review.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("끝난 주문(결제 완료, 포기)은 기한이 지나도 건드리지 않는다")
    void 끝난_주문은_건드리지_않는다() throws Exception {
        WinnerOrder paid = winnerOrder(0L);
        confirmFree(paid.bearer(), paid.eventId(), paid.orderId());
        WinnerOrder declined = winnerOrder();
        decline(declined.bearer(), declined.eventId(), declined.orderId());
        setDeadline(paid.orderId(), Instant.now().minusSeconds(1));
        setDeadline(declined.orderId(), Instant.now().minusSeconds(1));

        worker.expireDue();

        assertThat(orderStatus(paid.orderId())).isEqualTo("PAID");
        assertThat(selectionStatus(paid.orderId())).isEqualTo("CONFIRMED");
        assertThat(orderStatus(declined.orderId())).isEqualTo("CANCELLED");
        assertThat(selectionStatus(declined.orderId())).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("확정하지 않은 0원 주문도 기한이 지나면 만료한다")
    void 확정하지_않은_0원_주문도_만료한다() throws Exception {
        WinnerOrder order = winnerOrder(0L);
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));

        worker.expireDue();
        MvcResult late = confirmFree(order.bearer(), order.eventId(), order.orderId());

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("ORDER_NOT_PAYABLE");
    }

    @Test
    @DisplayName("만료된 주문은 포기할 수 없다 → 409 ORDER_NOT_PAYABLE")
    void 만료된_주문은_포기할_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));
        worker.expireDue();

        MvcResult res = decline(order.bearer(), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(res)).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("C14 승인 시작이 먼저 주문을 잠갔으면, 기다리던 만료는 잠근 뒤 다시 확인해 만료하지 않는다")
    void 승인_시작이_먼저면_만료하지_않는다() throws Exception {
        WinnerOrder order = winnerOrder();
        setDeadline(order.orderId(), Instant.now().plusSeconds(2));
        Long attempt = FAKE_PAYMENT_IDS.incrementAndGet();
        CountDownLatch approvalLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 결제 모듈의 승인 시작 트랜잭션: 기한 전에 주문을 잠그고 받아들인 뒤, 기한이 지날 때까지 커밋하지 않는다.
        CompletableFuture<ApprovalDecision> approval = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    ApprovalDecision decision = handler().onApprovalStarting(order.orderId(),
                            new PaymentAttemptContext(attempt, Instant.now()));
                    approvalLocked.countDown();
                    await(release);
                    return decision;
                }));
        assertThat(approvalLocked.await(30, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2500);
        // 커밋된 값으로는 아직 "결제 대기, 기한 경과, 시도 없음"이라 만료 후보다. 만료는 주문 잠금을 기다린다.
        CompletableFuture<Boolean> expiring = CompletableFuture.supplyAsync(() -> expiry.expire(order.orderId()));
        Thread.sleep(500);
        assertThat(expiring).as("만료는 주문 잠금을 기다린다").isNotDone();
        release.countDown();

        assertThat(approval.get(30, TimeUnit.SECONDS).accepted()).isTrue();
        assertThat(expiring.get(30, TimeUnit.SECONDS)).as("잠근 뒤 다시 확인해 만료하지 않는다").isFalse();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(orderColumn(order.orderId(), "payment_id")).isEqualTo(attempt);
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("C14 만료가 먼저면 그 뒤 승인 시작은 거절된다. EXPIRED인데 승인된 시도 0건 (INV-FM-10)")
    void 만료가_먼저면_승인_시작을_거절한다() throws Exception {
        WinnerOrder order = winnerOrder();
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));

        boolean expired = expiry.expire(order.orderId());
        ApprovalDecision decision = inTx(() -> handler().onApprovalStarting(order.orderId(),
                new PaymentAttemptContext(FAKE_PAYMENT_IDS.incrementAndGet(), Instant.now())));

        assertThat(expired).isTrue();
        assertThat(decision.rejected()).isTrue();
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(orderColumn(order.orderId(), "payment_id")).isNull();
    }

    @Test
    @DisplayName("C34 보류가 30분을 넘으면 경고 로그를 한 번만 남긴다. 자동 확정·탈락은 없다")
    void 보류_30분_경고는_한_번만(CapturedOutput output) throws Exception {
        WinnerOrder stale = reviewOrder();
        WinnerOrder fresh = reviewOrder();
        setReviewStartedAt(stale.orderId(), Instant.now().minus(Duration.ofMinutes(30)).minusSeconds(1));
        setReviewStartedAt(fresh.orderId(), Instant.now().minus(Duration.ofMinutes(29)));

        worker.warnStaleReviews();
        Object warnedAt = orderColumn(stale.orderId(), "review_warned_at");
        worker.warnStaleReviews();

        assertThat(warnedAt).isNotNull();
        assertThat(orderColumn(stale.orderId(), "review_warned_at")).isEqualTo(warnedAt);
        assertThat(occurrences(output.getAll(), "FANMEETING_PAYMENT_REVIEW_STALE orderId=" + stale.orderId() + " "))
                .isEqualTo(1);
        assertThat(orderStatus(stale.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(selectionStatus(stale.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(orderColumn(fresh.orderId(), "review_warned_at")).as("30분 전이면 경고하지 않는다").isNull();
        assertThat(output.getAll()).doesNotContain("FANMEETING_PAYMENT_REVIEW_STALE orderId=" + fresh.orderId() + " ");
    }

    @Test
    @DisplayName("C48 보류 중 결과 모름이 다시 와도 보류 시작 시각은 그대로이고, 경고는 처음 보류 기준 30분에 한 번")
    void 결과_모름이_반복돼도_보류_시작_시각은_그대로다(CapturedOutput output) throws Exception {
        WinnerOrder order = reviewOrder();
        setReviewStartedAt(order.orderId(), Instant.now().minus(Duration.ofMinutes(31)));
        Object startedAt = orderColumn(order.orderId(), "review_started_at");
        Long attempt = ((Number) orderColumn(order.orderId(), "payment_id")).longValue();

        inTx(() -> {
            handler().onUnknown(order.orderId(), new PaymentAttemptContext(attempt, Instant.now()));
            return null;
        });
        worker.warnStaleReviews();

        assertThat(orderColumn(order.orderId(), "review_started_at")).isEqualTo(startedAt);
        assertThat(occurrences(output.getAll(), "FANMEETING_PAYMENT_REVIEW_STALE orderId=" + order.orderId() + " "))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("보류에서 벗어났다가 다시 보류가 되면 새 보류 기준으로 다시 경고한다")
    void 새_보류는_다시_경고한다(CapturedOutput output) throws Exception {
        WinnerOrder order = reviewOrder();
        Long first = ((Number) orderColumn(order.orderId(), "payment_id")).longValue();
        setReviewStartedAt(order.orderId(), Instant.now().minus(Duration.ofMinutes(31)));
        worker.warnStaleReviews();
        inTx(() -> {
            handler().onFailed(order.orderId(), new PaymentAttemptContext(first, Instant.now()));
            return null;
        });
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(orderColumn(order.orderId(), "review_warned_at")).isNull();

        Long second = startApproval(order.orderId());
        inTx(() -> {
            handler().onUnknown(order.orderId(), new PaymentAttemptContext(second, Instant.now()));
            return null;
        });
        setReviewStartedAt(order.orderId(), Instant.now().minus(Duration.ofMinutes(31)));
        worker.warnStaleReviews();

        assertThat(occurrences(output.getAll(), "FANMEETING_PAYMENT_REVIEW_STALE orderId=" + order.orderId() + " "))
                .isEqualTo(2);
    }

    /** 승인 시작 뒤 결과 모름이 와서 보류가 된 주문. */
    WinnerOrder reviewOrder() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        inTx(() -> {
            handler().onUnknown(order.orderId(), new PaymentAttemptContext(attempt, Instant.now()));
            return null;
        });
        assertThat(orderStatus(order.orderId())).isEqualTo("PAYMENT_REVIEW");
        return order;
    }

    void setReviewStartedAt(Long orderId, Instant startedAt) {
        jdbc.update("UPDATE fan_meeting_orders SET review_started_at = ? WHERE order_id = ?", utc(startedAt), orderId);
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
