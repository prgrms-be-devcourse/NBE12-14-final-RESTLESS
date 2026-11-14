package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.restless.fanmeeting.application.FanMeetingDrawWorker;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.PaymentResultHandler;
import com.restless.payment.application.payable.PaymentResultHandlerRegistry;
import com.restless.payment.domain.PayableType;
import com.restless.payment.infrastructure.fake.FakePgClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 팬미팅 결제 (`transactions.md` 6·7절, `state-machines.md` 4절).
 * 공통 결제 API(`/api/payments`)와 Fake PG로 끝까지 실행하고, 결제 모듈이 부르는 Handler가 주문·선발 상태를 맞게 바꾸는지 본다.
 * 결제 모듈이 걸러 주어 API로는 만들 수 없는 결과(기한 경계, 같은 승인 두 번, 다른 시도의 승인)는 Handler를 직접 부른다.
 */
@DisplayName("팬미팅 결제")
class FanMeetingPaymentApiTest extends FanMeetingApiSupport {

    private static final AtomicLong FAKE_PAYMENT_IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired FanMeetingDrawWorker drawWorker;
    @Autowired FakePgClient fakePg;
    @Autowired PaymentResultHandlerRegistry handlers;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    // ---- 공통 결제 API로 끝까지 ----

    @Test
    @DisplayName("당첨자가 결제하면 주문 PAID, 선발 CONFIRMED. 금액은 참가비, 기한은 발급 + 60분")
    void 기한_안에_결제하면_확정된다() throws Exception {
        WinnerOrder order = winnerOrder();

        Prepared p = prepare(order.bearer(), order.orderId(), "first");
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        assertThat(p.amount()).isEqualTo(50000L);
        assertThat(Instant.parse(p.expiresAt())).isEqualTo(issuedAt(order.orderId()).plusSeconds(3600));

        fakePg.willApprove(p.pgOrderId());
        MvcResult confirmed = confirm(order.bearer(), p);

        assertThat(confirmed.getResponse().getStatus()).as(contentOf(confirmed)).isEqualTo(200);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(selectionStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).isEqualTo(String.valueOf(p.paymentId()));
    }

    @Test
    @DisplayName("C26 다른 회원이 내 주문으로 결제 준비 → 404, 주문 변화 없음")
    void 다른_회원은_내_주문을_결제할_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();

        Prepared p = prepare(token("USER"), order.orderId(), "other");

        assertThat(p.status()).as(p.body()).isEqualTo(404);
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).isNull();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("결제 준비를 요청하면 처음 시각을 기록하고, 다시 요청해도 바꾸지 않는다(결제 포기 판정)")
    void 결제_준비_요청_시각은_처음_한_번만_기록한다() throws Exception {
        WinnerOrder order = winnerOrder();

        prepare(order.bearer(), order.orderId(), "first");
        Object first = orderColumn(order.orderId(), "attempt_requested_at");
        prepare(order.bearer(), order.orderId(), "second");

        assertThat(first).isNotNull();
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).isEqualTo(first);
    }

    @Test
    @DisplayName("결제 대기 중이 아닌 행사(모집 완료)의 주문은 결제를 준비할 수 없다")
    void 모집_완료된_행사는_결제할_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();
        jdbc.update("UPDATE fan_meeting_events SET status = 'RECRUITMENT_COMPLETED' WHERE event_id = ?",
                order.eventId());

        Prepared p = prepare(order.bearer(), order.orderId(), "completed");

        assertThat(p.status()).as(p.body()).isEqualTo(409);
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).isNull();
    }

    @Test
    @DisplayName("C10 결제 실패, 기한 남음 → 결제 대기 유지, 새 키로 다시 결제할 수 있다")
    void 기한_전_실패는_다시_결제할_수_있다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared first = prepare(order.bearer(), order.orderId(), "first");
        fakePg.willReject(first.pgOrderId(), "REJECT_CARD_COMPANY");

        confirm(order.bearer(), first);

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(orderColumn(order.orderId(), "payment_id")).isNull();

        Prepared retry = prepare(order.bearer(), order.orderId(), "retry");
        fakePg.willApprove(retry.pgOrderId());
        confirm(order.bearer(), retry);

        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).isEqualTo(String.valueOf(retry.paymentId()));
    }

    @Test
    @DisplayName("결제 결과를 모르면 보류(PAYMENT_REVIEW)로 자리를 지키고 보류 시작 시각을 기록한다")
    void 결과를_모르면_보류한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "unknown");
        fakePg.willTimeout(p.pgOrderId());

        MvcResult res = confirm(order.bearer(), p);

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(202);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(orderColumn(order.orderId(), "review_started_at")).isNotNull();
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("승인 시작은 기한 안이었지만 승인이 기한 뒤 → 거절, 결제 모듈이 환불(REFUNDED), 선발 EXPIRED")
    void 늦은_승인은_환불된다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "late");
        fakePg.willDelay(p.pgOrderId(), Duration.ofSeconds(3));
        fakePg.willApprove(p.pgOrderId());

        MvcResult res = confirmWhileDeadlinePasses(order, p);

        assertThat(orderStatus(order.orderId())).as(contentOf(res)).isEqualTo("REFUNDED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).isEqualTo(String.valueOf(p.paymentId()));
    }

    @Test
    @DisplayName("C10 결제 실패가 기한 뒤에 확정 → 주문·선발 EXPIRED")
    void 기한_뒤_실패는_만료된다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "fail-late");
        fakePg.willDelay(p.pgOrderId(), Duration.ofSeconds(3));
        fakePg.willReject(p.pgOrderId(), "REJECT_CARD_COMPANY");

        confirmWhileDeadlinePasses(order, p);

        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("결제창만 열고 기한이 지난 뒤 승인 요청 → 결제 모듈이 시도를 닫고, 주문·선발 EXPIRED")
    void 승인_시작_전에_기한이_지나면_만료된다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "not-started");
        Instant past = Instant.now().minus(Duration.ofMinutes(61));
        jdbc.update("UPDATE fan_meeting_orders SET issued_at = ? WHERE order_id = ?", utc(past), order.orderId());
        jdbc.update("UPDATE payments SET expires_at = ? WHERE id = ?", utc(past.plus(Duration.ofMinutes(60))),
                p.paymentId());

        MvcResult res = confirm(order.bearer(), p);

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(409);
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("C27 승인 금액이 주문 금액과 다름 → 거절, 결제 모듈이 환불(REFUNDED), 선발 EXPIRED")
    void 금액이_다르면_환불된다() throws Exception {
        WinnerOrder order = winnerOrder();
        Prepared p = prepare(order.bearer(), order.orderId(), "amount");
        jdbc.update("UPDATE fan_meeting_orders SET amount = 40000 WHERE order_id = ?", order.orderId());
        fakePg.willApprove(p.pgOrderId());

        confirm(order.bearer(), p);

        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    // ---- Handler 직접 호출 (결제 모듈이 걸러 주는 경우) ----

    @Test
    @DisplayName("Handler는 결제 모듈의 트랜잭션 안에서만 실행된다(MANDATORY)")
    void 트랜잭션_밖에서는_부를_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();

        assertThatThrownBy(() -> handler().load(order.orderId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("승인 시작: 기한 1초 전이면 받아들이고, 기한 1초 뒤면 거절한다")
    void 승인_시작은_기한_전에만_받는다() throws Exception {
        WinnerOrder before = winnerOrder();
        WinnerOrder after = winnerOrder();
        setDeadline(before.orderId(), Instant.now().plusSeconds(1));
        setDeadline(after.orderId(), Instant.now().minusSeconds(1));

        Long beforeAttempt = FAKE_PAYMENT_IDS.incrementAndGet();
        ApprovalDecision accepted = inTx(() -> handler().onApprovalStarting(before.orderId(),
                new PaymentAttemptContext(beforeAttempt, Instant.now())));
        ApprovalDecision rejected = inTx(() -> handler().onApprovalStarting(after.orderId(),
                new PaymentAttemptContext(FAKE_PAYMENT_IDS.incrementAndGet(), Instant.now())));

        assertThat(accepted.accepted()).isTrue();
        assertThat(orderColumn(before.orderId(), "payment_id")).isEqualTo(beforeAttempt);
        assertThat(rejected.rejected()).isTrue();
        assertThat(orderColumn(after.orderId(), "payment_id")).isNull();
    }

    @Test
    @DisplayName("승인 시각이 기한과 같으면 기한 안(PAID), 기한 1초 뒤면 환불 대기")
    void 승인_시각이_기한과_같으면_기한_안이다() throws Exception {
        WinnerOrder same = winnerOrder();
        WinnerOrder late = winnerOrder();
        Long sameAttempt = startApproval(same.orderId());
        Long lateAttempt = startApproval(late.orderId());

        ApprovalDecision onTime = inTx(() -> handler().onApproved(same.orderId(),
                approved(sameAttempt, deadline(same.orderId()))));
        ApprovalDecision tooLate = inTx(() -> handler().onApproved(late.orderId(),
                approved(lateAttempt, deadline(late.orderId()).plusSeconds(1))));

        assertThat(onTime.accepted()).isTrue();
        assertThat(orderStatus(same.orderId())).isEqualTo("PAID");
        assertThat(tooLate.rejected()).isTrue();
        assertThat(orderStatus(late.orderId())).isEqualTo("REFUND_PENDING");
        assertThat(selectionStatus(late.orderId())).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("C09 같은 승인이 두 번 와도 한 번만 반영한다")
    void 같은_승인_두_번은_한_번만_반영한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        ApprovedPayment payment = approved(attempt, Instant.now());

        ApprovalDecision first = inTx(() -> handler().onApproved(order.orderId(), payment));
        Object paidAt = orderColumn(order.orderId(), "paid_at");
        ApprovalDecision second = inTx(() -> handler().onApproved(order.orderId(), payment));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(orderColumn(order.orderId(), "paid_at")).isEqualTo(paidAt);
    }

    @Test
    @DisplayName("C47 결제 완료 주문에 다른 시도의 승인(이중 결제) → 거절해 결제 모듈이 환불, 주문은 그대로")
    void 다른_시도의_승인은_거절한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        inTx(() -> handler().onApproved(order.orderId(), approved(attempt, Instant.now())));

        Long other = FAKE_PAYMENT_IDS.incrementAndGet();
        ApprovalDecision decision = inTx(() -> handler().onApproved(order.orderId(), approved(other, Instant.now())));

        assertThat(decision.rejected()).isTrue();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).isEqualTo(String.valueOf(attempt));
        assertThat(selectionStatus(order.orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("보류 중인 주문에 현재 시도가 아닌 승인이 오면 거절하고 보류를 유지한다")
    void 보류_중_다른_시도의_승인은_거절한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        inTx(() -> {
            handler().onUnknown(order.orderId(), new PaymentAttemptContext(attempt, Instant.now()));
            return null;
        });

        Long other = FAKE_PAYMENT_IDS.incrementAndGet();
        ApprovalDecision decision = inTx(() -> handler().onApproved(order.orderId(), approved(other, Instant.now())));

        assertThat(decision.rejected()).isTrue();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("보류 중인 주문에 현재 시도가 아닌 실패가 오면 무시하고 보류를 유지한다")
    void 보류_중_다른_시도의_실패는_무시한다() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        inTx(() -> {
            handler().onUnknown(order.orderId(), new PaymentAttemptContext(attempt, Instant.now()));
            return null;
        });

        Long other = FAKE_PAYMENT_IDS.incrementAndGet();
        inTx(() -> {
            handler().onFailed(order.orderId(), new PaymentAttemptContext(other, Instant.now()));
            return null;
        });

        assertThat(orderStatus(order.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(orderColumn(order.orderId(), "payment_id")).isEqualTo(attempt);
    }

    @Test
    @DisplayName("C49 만료된 주문에 승인이 오면 거절하고 자리를 되돌리지 않는다")
    void 만료_뒤_승인은_자리를_되돌리지_않는다() throws Exception {
        WinnerOrder order = winnerOrder();
        Long attempt = startApproval(order.orderId());
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));
        inTx(() -> {
            handler().onFailed(order.orderId(), new PaymentAttemptContext(attempt, Instant.now()));
            return null;
        });
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");

        ApprovalDecision decision = inTx(() -> handler().onApproved(order.orderId(),
                approved(attempt, Instant.now().minusSeconds(5))));

        assertThat(decision.rejected()).isTrue();
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("EXPIRED");
    }

    // ---- 도우미 ----

    record WinnerOrder(Long eventId, Long orderId, String bearer) {
    }

    record Prepared(int status, String body, Long paymentId, String pgOrderId, long amount, String expiresAt) {
    }

    /** 응모 1명, 당첨 1명인 행사를 추첨해 당첨 주문 하나를 만든다. */
    WinnerOrder winnerOrder() throws Exception {
        Long eventId = closedEventWithApplicants(1, 0, 1);
        drawWorker.drawDue();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT order_id, user_id FROM fan_meeting_orders WHERE event_id = ?", eventId);
        Long orderId = ((Number) row.get("order_id")).longValue();
        Long userId = ((Number) row.get("user_id")).longValue();
        return new WinnerOrder(eventId, orderId, "Bearer " + tokenProvider.issue(userId, "USER").value());
    }

    /** 공통 결제 준비. 결제 대상은 FAN_MEETING, 대상 id는 주문 id. */
    Prepared prepare(String bearer, Long orderId, String key) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payableType\":\"FAN_MEETING\",\"payableId\":" + orderId + "}"))
                .andReturn();
        String body = contentOf(res);
        int status = res.getResponse().getStatus();
        if (status >= 300) {
            return new Prepared(status, body, null, null, 0, null);
        }
        return new Prepared(status, body, ((Number) JsonPath.read(body, "$.paymentId")).longValue(),
                JsonPath.read(body, "$.pgOrderId"), ((Number) JsonPath.read(body, "$.amount")).longValue(),
                JsonPath.read(body, "$.expiresAt"));
    }

    /** 결제창에서 돌아와 승인 요청. */
    MvcResult confirm(String bearer, Prepared p) throws Exception {
        return mockMvc.perform(post("/api/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"pk-%s","orderId":"%s","amount":%d}
                                """.formatted(UUID.randomUUID(), p.pgOrderId(), p.amount())))
                .andReturn();
    }

    /**
     * 승인 시작은 기한 안에 받아들여지고, PG 응답을 기다리는 동안(Fake PG 3초 지연) 기한이 지나게 한다.
     */
    MvcResult confirmWhileDeadlinePasses(WinnerOrder order, Prepared p) throws Exception {
        CompletableFuture<MvcResult> confirming = CompletableFuture.supplyAsync(() -> {
            try {
                return confirm(order.bearer(), p);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1500);
        assertThat(orderColumn(order.orderId(), "payment_id")).as("승인 시작은 받아들여졌다").isEqualTo(p.paymentId());
        setDeadline(order.orderId(), Instant.now().minusSeconds(1));
        return confirming.get();
    }

    PaymentResultHandler handler() {
        return handlers.get(PayableType.FAN_MEETING);
    }

    /** 결제 모듈처럼 트랜잭션을 열고 Handler를 부른다. */
    <T> T inTx(Supplier<T> call) {
        return new TransactionTemplate(transactionManager).execute(status -> call.get());
    }

    /** 결제 모듈 없이 승인 시작을 받아들인 상태로 만든다. 시도 번호를 돌려준다. */
    Long startApproval(Long orderId) {
        Long attempt = FAKE_PAYMENT_IDS.incrementAndGet();
        ApprovalDecision decision = inTx(() -> handler().onApprovalStarting(orderId,
                new PaymentAttemptContext(attempt, Instant.now())));
        assertThat(decision.accepted()).isTrue();
        return attempt;
    }

    static ApprovedPayment approved(Long attempt, Instant approvedAt) {
        return new ApprovedPayment(attempt, "pg-" + attempt, "pk-" + attempt, 50000L,
                approvedAt.minusSeconds(1), approvedAt);
    }

    /** 결제 기한(발급 + 60분)이 deadline이 되도록 발급 시각을 옮긴다. */
    void setDeadline(Long orderId, Instant deadline) {
        jdbc.update("UPDATE fan_meeting_orders SET issued_at = ? WHERE order_id = ?",
                utc(deadline.minus(Duration.ofMinutes(60))), orderId);
    }

    Instant deadline(Long orderId) {
        return issuedAt(orderId).plus(Duration.ofMinutes(60));
    }

    String orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM fan_meeting_orders WHERE order_id = ?", String.class, orderId);
    }

    Object orderColumn(Long orderId, String column) {
        return jdbc.queryForMap("SELECT " + column + " AS v FROM fan_meeting_orders WHERE order_id = ?", orderId)
                .get("v");
    }

    String selectionStatus(Long orderId) {
        return jdbc.queryForObject("""
                SELECT s.status FROM fan_meeting_selections s
                  JOIN fan_meeting_orders o ON o.selection_id = s.selection_id
                 WHERE o.order_id = ?
                """, String.class, orderId);
    }

    Instant issuedAt(Long orderId) {
        return jdbc.queryForObject("SELECT issued_at FROM fan_meeting_orders WHERE order_id = ?",
                LocalDateTime.class, orderId).toInstant(ZoneOffset.UTC);
    }
}
