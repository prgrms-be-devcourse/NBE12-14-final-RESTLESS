package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.restless.fanmeeting.application.FanMeetingDrawWorker;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.PaymentResultHandler;
import com.restless.payment.application.payable.PaymentResultHandlerRegistry;
import com.restless.payment.domain.PayableType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 팬미팅 주문 테스트 공통: 당첨 주문 만들기, 공통 결제 준비·승인, 주문·선발 상태 읽기, 기한 옮기기. */
abstract class FanMeetingOrderApiSupport extends FanMeetingApiSupport {

    /** 결제 모듈 없이 Handler를 부를 때 쓰는 가짜 결제 시도 번호. 실제 payments.id와 겹치지 않게 크게 잡는다. */
    static final AtomicLong FAKE_PAYMENT_IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired FanMeetingDrawWorker drawWorker;
    @Autowired PaymentResultHandlerRegistry handlers;
    @Autowired PlatformTransactionManager transactionManager;

    record WinnerOrder(Long eventId, Long orderId, String bearer) {
    }

    record Prepared(int status, String body, Long paymentId, String pgOrderId, long amount, String expiresAt) {
    }

    /** 응모 1명, 당첨 1명인 행사를 추첨해 당첨 주문 하나를 만든다. 참가비 50,000원. */
    WinnerOrder winnerOrder() throws Exception {
        return winnerOrder(50000L);
    }

    /** 참가비를 정해 당첨 주문 하나를 만든다. 주문 금액은 추첨 때의 참가비다. */
    WinnerOrder winnerOrder(long feeAmount) throws Exception {
        Long eventId = closedEventWithApplicants(1, 0, 1);
        jdbc.update("UPDATE fan_meeting_events SET fee_amount = ? WHERE event_id = ?", feeAmount, eventId);
        drawWorker.drawDue();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT order_id, user_id FROM fan_meeting_orders WHERE event_id = ?", eventId);
        Long orderId = ((Number) row.get("order_id")).longValue();
        Long userId = ((Number) row.get("user_id")).longValue();
        return new WinnerOrder(eventId, orderId, bearerOf(userId));
    }

    String bearerOf(Long userId) {
        return "Bearer " + tokenProvider.issue(userId, "USER").value();
    }

    /** 결제 포기. */
    MvcResult decline(String bearer, Long eventId, Long orderId) throws Exception {
        return mockMvc.perform(post("/api/fan-meetings/{eventId}/orders/{orderId}/decline", eventId, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn();
    }

    /** 0원 주문 참가 확정. */
    MvcResult confirmFree(String bearer, Long eventId, Long orderId) throws Exception {
        return mockMvc.perform(post("/api/fan-meetings/{eventId}/orders/{orderId}/confirm", eventId, orderId)
                .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn();
    }

    MvcResult myOrder(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(get("/api/fan-meetings/{eventId}/orders/me", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn();
    }

    MvcResult myResult(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(get("/api/fan-meetings/{eventId}/result/me", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn();
    }

    static String codeOf(MvcResult result) {
        return JsonPath.read(contentOf(result), "$.code");
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
}
