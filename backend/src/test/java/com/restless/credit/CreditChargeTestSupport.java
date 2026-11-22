package com.restless.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auction.AuctionFixtures;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.payment.application.PaymentReconciler;
import com.restless.payment.application.payable.PaymentResultHandler;
import com.restless.payment.application.payable.PaymentResultHandlerRegistry;
import com.restless.payment.domain.PayableType;
import com.restless.payment.infrastructure.fake.FakePgClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 크레딧 충전 테스트 공통 (#316, 설계 `.ai-output/auction/reports/credit-charge-spec.md`).
 * 충전 주문은 API로 만들고, 결제는 공통 결제 API와 Fake PG로 끝까지 실행한다.
 */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class CreditChargeTestSupport {
    static final String CREDIT_CHARGE = "CREDIT_CHARGE";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakePgClient fakePg;
    @Autowired PaymentReconciler reconciler;
    @Autowired PaymentResultHandlerRegistry handlers;
    @Autowired PlatformTransactionManager transactionManager;
    AuctionFixtures f;

    record Prepared(int status, String body, Long paymentId, String pgOrderId, long amount) {
    }

    @BeforeEach
    void setUpCharge() {
        f = new AuctionFixtures(jdbc);
        f.clean();
        fakePg.reset();
    }

    String bearer(Long userId) {
        return "Bearer " + tokenProvider.issue(userId, "USER").value();
    }

    static String contentOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    static String codeOf(MvcResult result) {
        return JsonPath.read(contentOf(result), "$.code");
    }

    // ---- 충전 주문 ----

    MvcResult createCharge(Long userId, String body, String key) throws Exception {
        var request = post("/api/me/credit-charges")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mockMvc.perform(request).andReturn();
    }

    MvcResult createCharge(Long userId, long amount, String key) throws Exception {
        return createCharge(userId, "{\"amount\":" + amount + "}", key);
    }

    /** 충전 주문을 만들고 ID를 돌려준다. 201이 아니면 실패한다. */
    Long newCharge(Long userId, long amount) throws Exception {
        MvcResult res = createCharge(userId, amount, UUID.randomUUID().toString());
        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(201);
        return ((Number) JsonPath.read(contentOf(res), "$.chargeId")).longValue();
    }

    Map<String, Object> chargeRow(Long chargeId) {
        return jdbc.queryForMap("SELECT * FROM credit_charges WHERE credit_charge_id = ?", chargeId);
    }

    String chargeStatus(Long chargeId) {
        return jdbc.queryForObject("SELECT status FROM credit_charges WHERE credit_charge_id = ?", String.class,
                chargeId);
    }

    Long paidPaymentId(Long chargeId) {
        return jdbc.queryForObject("SELECT paid_payment_id FROM credit_charges WHERE credit_charge_id = ?",
                Long.class, chargeId);
    }

    int chargeCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM credit_charges WHERE user_id = ?", Integer.class, userId);
    }

    /** 결제 기한을 옮긴다. 시각은 UTC 벽시계 값으로 넣는다. */
    void setChargeExpiresAt(Long chargeId, Instant expiresAt) {
        jdbc.update("UPDATE credit_charges SET expires_at = ? WHERE credit_charge_id = ?",
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), chargeId);
    }

    // ---- 지갑과 내역 ----

    /** 지갑 보유액. 지갑이 없으면 0. */
    long balance(Long userId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(balance_amount), 0) FROM credit_wallets WHERE user_id = ?", Long.class, userId);
        return v == null ? 0 : v;
    }

    /** 회원의 CHARGE 내역 (operation_key, balance_delta, reserved_delta, credit_hold_id). */
    List<Map<String, Object>> chargeTransactions(Long userId) {
        return jdbc.queryForList("""
                SELECT t.operation_key, t.balance_delta, t.reserved_delta, t.credit_hold_id
                  FROM credit_transactions t
                  JOIN credit_wallets w ON w.credit_wallet_id = t.credit_wallet_id
                 WHERE w.user_id = ? AND t.transaction_type = 'CHARGE'
                """, userId);
    }

    /** 충전 주문 하나가 정확히 한 번, 그 금액만큼 지급됐는지 확인한다 (INV-AU-12, INV-AU-16). */
    void assertGrantedOnce(Long userId, Long chargeId, long amount) {
        assertThat(chargeStatus(chargeId)).isEqualTo("PAID");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.balance_delta, t.reserved_delta FROM credit_transactions t
                 WHERE t.transaction_type = 'CHARGE' AND t.operation_key = ?
                """, "CHARGE:" + chargeId);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("balance_delta")).longValue()).isEqualTo(amount);
        assertThat(((Number) rows.get(0).get("reserved_delta")).longValue()).isZero();
    }

    /**
     * INV-AU-17: PAID 충전 주문의 지급 결제는 이 충전 주문의 CREDIT_CHARGE 결제이고, 같은 회원·같은 금액이며 APPROVED다.
     * 처리기를 직접 부르는 테스트는 가짜 결제 ID를 쓰므로 불변식 SQL 파일이 아니라 API 흐름 테스트에서 확인한다.
     */
    void assertPaidBy(Long chargeId, Long paymentId) {
        assertThat(paidPaymentId(chargeId)).isEqualTo(paymentId);
        Map<String, Object> charge = chargeRow(chargeId);
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT payable_type, payable_id, user_id, amount, status FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("payable_type")).isEqualTo(CREDIT_CHARGE);
        assertThat(((Number) payment.get("payable_id")).longValue()).isEqualTo(chargeId);
        assertThat(((Number) payment.get("user_id")).longValue())
                .isEqualTo(((Number) charge.get("user_id")).longValue());
        assertThat(((Number) payment.get("amount")).longValue())
                .isEqualTo(((Number) charge.get("amount")).longValue());
        assertThat(payment.get("status")).isEqualTo("APPROVED");
    }

    /** 지급이 없었는지 확인한다: 충전 주문 READY, CHARGE 내역 없음. */
    void assertNotGranted(Long userId, Long chargeId) {
        assertThat(chargeStatus(chargeId)).isEqualTo("READY");
        assertThat(paidPaymentId(chargeId)).isNull();
        assertThat(chargeTransactions(userId)).isEmpty();
    }

    // ---- 공통 결제 ----

    Prepared prepare(Long userId, Long chargeId, String key) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payableType\":\"" + CREDIT_CHARGE + "\",\"payableId\":" + chargeId + "}"))
                .andReturn();
        String body = contentOf(res);
        int status = res.getResponse().getStatus();
        if (status >= 300) return new Prepared(status, body, null, null, 0);
        return new Prepared(status, body, ((Number) JsonPath.read(body, "$.paymentId")).longValue(),
                JsonPath.read(body, "$.pgOrderId"), ((Number) JsonPath.read(body, "$.amount")).longValue());
    }

    Prepared prepareOk(Long userId, Long chargeId) throws Exception {
        Prepared p = prepare(userId, chargeId, UUID.randomUUID().toString());
        assertThat(p.status()).as(p.body()).isEqualTo(201);
        return p;
    }

    MvcResult confirm(Long userId, Prepared p) throws Exception {
        return confirm(userId, p, "pk-" + UUID.randomUUID());
    }

    MvcResult confirm(Long userId, Prepared p, String paymentKey) throws Exception {
        return mockMvc.perform(post("/api/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentKey":"%s","orderId":"%s","amount":%d}
                                """.formatted(paymentKey, p.pgOrderId(), p.amount())))
                .andReturn();
    }

    /** 충전 주문을 만들고 Fake PG 승인까지 끝낸다. 충전 주문 ID를 돌려준다. */
    Long paidCharge(Long userId, long amount) throws Exception {
        Long chargeId = newCharge(userId, amount);
        Prepared p = prepareOk(userId, chargeId);
        fakePg.willApprove(p.pgOrderId());
        MvcResult res = confirm(userId, p);
        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        return chargeId;
    }

    String paymentStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    int paymentCount(Long chargeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE payable_type = ? AND payable_id = ?",
                Integer.class, CREDIT_CHARGE, chargeId);
    }

    /**
     * 미완료 결제 재확인 작업이 이 결제를 다시 보도록 결제 시각을 16분 앞당긴다.
     * 재시도 대기(10초)와 PG 미조회 최종 판정(15분)을 모두 넘긴다.
     */
    void ageForReconcile(Long paymentId) {
        jdbc.update("""
                UPDATE payments
                   SET updated_at = DATE_SUB(updated_at, INTERVAL 16 MINUTE),
                       approval_started_at = DATE_SUB(approval_started_at, INTERVAL 16 MINUTE)
                 WHERE id = ?
                """, paymentId);
    }

    // ---- 결제 결과 처리기 직접 호출 ----

    static PayableType creditChargeType() {
        return PayableType.CREDIT_CHARGE;
    }

    PaymentResultHandler handler() {
        return handlers.get(creditChargeType());
    }

    /** 결제 모듈처럼 트랜잭션을 열고 처리기를 부른다. */
    <T> T inTx(Supplier<T> call) {
        return new TransactionTemplate(transactionManager).execute(status -> call.get());
    }

    void inTx(Runnable call) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> call.run());
    }
}
