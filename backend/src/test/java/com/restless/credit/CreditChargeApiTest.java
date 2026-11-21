package com.restless.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 크레딧 충전 API와 공통 결제 흐름 (#316). 설계의 C-01, C-02, C-04~C-07, C-09~C-13, C-16.
 * 충전 주문 생성 → 공통 결제 준비 → Fake PG 승인 → 지갑 지급을 HTTP로 끝까지 실행한다.
 */
@DisplayName("크레딧 충전 API")
class CreditChargeApiTest extends CreditChargeTestSupport {

    // ---- 충전 주문 생성 ----

    @Test
    @DisplayName("C-01·C-16 지갑이 없는 회원이 5만 크레딧을 충전하면 지갑이 생기고 정확히 한 번 지급된다")
    void 충전하면_지갑에_지급된다() throws Exception {
        Long user = f.user("USER");
        Instant before = Instant.now();

        MvcResult created = createCharge(user, 50_000, "charge-1");

        assertThat(created.getResponse().getStatus()).as(contentOf(created)).isEqualTo(201);
        String body = contentOf(created);
        Long chargeId = ((Number) JsonPath.read(body, "$.chargeId")).longValue();
        assertThat(((Number) JsonPath.read(body, "$.amount")).longValue()).isEqualTo(50_000);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("READY");
        assertThat(Instant.parse(JsonPath.read(body, "$.expiresAt")))
                .isCloseTo(before.plus(30, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));
        assertThat((String) JsonPath.read(body, "$.payable.payableType")).isEqualTo(CREDIT_CHARGE);
        assertThat(((Number) JsonPath.read(body, "$.payable.payableId")).longValue()).isEqualTo(chargeId);
        assertThat(balance(user)).isZero();

        Prepared p = prepareOk(user, chargeId);
        assertThat(p.amount()).isEqualTo(50_000);
        assertThat((String) JsonPath.read(p.body(), "$.orderName")).isEqualTo("크레딧 50,000");

        fakePg.willApprove(p.pgOrderId());
        MvcResult confirmed = confirm(user, p);

        assertThat(confirmed.getResponse().getStatus()).as(contentOf(confirmed)).isEqualTo(200);
        assertThat(paymentStatus(p.paymentId())).isEqualTo("APPROVED");
        assertGrantedOnce(user, chargeId, 50_000);
        assertPaidBy(chargeId, p.paymentId());
        assertThat(chargeRow(chargeId).get("paid_at")).isNotNull();
        assertThat(chargeTransactions(user)).singleElement().satisfies(t -> {
            assertThat(t.get("operation_key")).isEqualTo("CHARGE:" + chargeId);
            assertThat(t.get("credit_hold_id")).isNull();
        });
        mockMvc.perform(get("/api/me/wallet").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(50_000))
                .andExpect(jsonPath("$.reservedAmount").value(0))
                .andExpect(jsonPath("$.availableAmount").value(50_000));
        f.assertInvariants();
    }

    @Test
    @DisplayName("이미 크레딧이 있는 지갑에는 충전 금액만큼 더해진다")
    void 기존_지갑에_더해진다() throws Exception {
        Long user = f.user("USER");
        f.credit(user, 10_000);

        Long chargeId = paidCharge(user, 20_000);

        assertThat(balance(user)).isEqualTo(30_000);
        assertGrantedOnce(user, chargeId, 20_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("C-11 같은 요청 키·같은 금액은 같은 충전 주문(200), 결제 뒤에 다시 보내도 같은 주문(PAID)")
    void 같은_요청_키는_같은_충전_주문을_준다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = ((Number) JsonPath.read(contentOf(createCharge(user, 10_000, "same")), "$.chargeId"))
                .longValue();

        MvcResult again = createCharge(user, 10_000, "same");

        assertThat(again.getResponse().getStatus()).as(contentOf(again)).isEqualTo(200);
        assertThat(((Number) JsonPath.read(contentOf(again), "$.chargeId")).longValue()).isEqualTo(chargeId);
        assertThat(chargeCount(user)).isEqualTo(1);

        Prepared p = prepareOk(user, chargeId);
        fakePg.willApprove(p.pgOrderId());
        confirm(user, p);
        MvcResult afterPaid = createCharge(user, 10_000, "same");

        assertThat(afterPaid.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(contentOf(afterPaid), "$.status")).isEqualTo("PAID");
        assertThat(chargeCount(user)).isEqualTo(1);
        assertGrantedOnce(user, chargeId, 10_000);
    }

    @Test
    @DisplayName("C-11 같은 요청 키로 다른 금액을 보내면 409 IDEMPOTENCY_KEY_CONFLICT, 새 주문 없음")
    void 같은_키_다른_금액은_409() throws Exception {
        Long user = f.user("USER");
        createCharge(user, 10_000, "same");

        MvcResult conflict = createCharge(user, 20_000, "same");

        assertThat(conflict.getResponse().getStatus()).as(contentOf(conflict)).isEqualTo(409);
        assertThat(codeOf(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(chargeCount(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("C-11 같은 요청 키·같은 금액이 동시에 와도 충전 주문은 하나, 한쪽은 201·다른 쪽은 200")
    void 같은_요청_키가_동시에_와도_주문은_하나다() throws Exception {
        for (int round = 0; round < 10; round++) {
            Long user = f.user("USER");
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<MvcResult> call = () -> {
                    start.await();
                    return createCharge(user, 10_000, "race");
                };
                var a = pool.submit(call);
                var b = pool.submit(call);
                start.countDown();
                MvcResult ra = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
                MvcResult rb = b.get(10, java.util.concurrent.TimeUnit.SECONDS);

                assertThat(java.util.List.of(ra.getResponse().getStatus(), rb.getResponse().getStatus()))
                        .as("round %d: %s / %s", round, contentOf(ra), contentOf(rb))
                        .containsExactlyInAnyOrder(201, 200);
                assertThat(JsonPath.<Object>read(contentOf(ra), "$.chargeId"))
                        .isEqualTo(JsonPath.read(contentOf(rb), "$.chargeId"));
                assertThat(chargeCount(user)).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("다른 회원이 같은 요청 키를 써도 서로 영향이 없다")
    void 요청_키는_회원마다_따로다() throws Exception {
        Long a = f.user("USER");
        Long b = f.user("USER");

        MvcResult first = createCharge(a, 10_000, "shared-key");
        MvcResult second = createCharge(b, 20_000, "shared-key");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).as(contentOf(second)).isEqualTo(201);
        assertThat(chargeCount(a)).isEqualTo(1);
        assertThat(chargeCount(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("금액 경계: 1,000과 1,000,000은 받는다")
    void 금액_경계값은_받는다() throws Exception {
        Long user = f.user("USER");

        assertThat(createCharge(user, 1_000, "min").getResponse().getStatus()).isEqualTo(201);
        assertThat(createCharge(user, 1_000_000, "max").getResponse().getStatus()).isEqualTo(201);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"amount\":999}",
            "{\"amount\":1000001}",
            "{\"amount\":1500}",
            "{\"amount\":0}",
            "{\"amount\":-1000}",
            "{\"amount\":1000.5}",
            "{\"amount\":\"abc\"}",
            "{\"amount\":null}",
            "{}"
    })
    @DisplayName("C-12 금액이 없거나, 정수가 아니거나, 범위 밖이거나, 1,000 단위가 아니면 400, 저장 없음")
    void 잘못된_금액은_400(String body) throws Exception {
        Long user = f.user("USER");

        MvcResult res = createCharge(user, body, UUID.randomUUID().toString());

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("INVALID_REQUEST");
        assertThat(chargeCount(user)).isZero();
    }

    @Test
    @DisplayName("Idempotency-Key가 없거나, 공백이거나, 64자를 넘으면 400")
    void 요청_키가_잘못되면_400() throws Exception {
        Long user = f.user("USER");

        for (String key : new String[] {null, "   ", "k".repeat(65)}) {
            MvcResult res = createCharge(user, 10_000, key);
            assertThat(res.getResponse().getStatus()).as("key=%s %s", key, contentOf(res)).isEqualTo(400);
            assertThat(codeOf(res)).isEqualTo("INVALID_REQUEST");
        }
        assertThat(createCharge(user, 10_000, "k".repeat(64)).getResponse().getStatus()).isEqualTo(201);
        assertThat(chargeCount(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/me/credit-charges")
                        .header("Idempotency-Key", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10000}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 결제 결과 ----

    @Test
    @DisplayName("C-02 같은 승인 요청을 두 번 보내도 PG는 한 번, 지급도 한 번")
    void 같은_승인_요청_두_번은_한_번만_지급한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 30_000);
        Prepared p = prepareOk(user, chargeId);
        fakePg.willApprove(p.pgOrderId());

        MvcResult first = confirm(user, p, "pk-same");
        MvcResult second = confirm(user, p, "pk-same");

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).as(contentOf(second)).isEqualTo(200);
        assertThat(balance(user)).isEqualTo(30_000);
        assertGrantedOnce(user, chargeId, 30_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("C-06 PG가 거절하면 409 PAYMENT_FAILED, 지급 없음. 새 결제로 다시 충전할 수 있다")
    void 거절되면_지급하지_않고_다시_결제할_수_있다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Prepared first = prepareOk(user, chargeId);
        fakePg.willReject(first.pgOrderId(), "REJECT_CARD_COMPANY");

        MvcResult rejected = confirm(user, first);

        assertThat(rejected.getResponse().getStatus()).as(contentOf(rejected)).isEqualTo(409);
        assertThat(codeOf(rejected)).isEqualTo("PAYMENT_FAILED");
        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isZero();

        Prepared retry = prepareOk(user, chargeId);
        fakePg.willApprove(retry.pgOrderId());
        confirm(user, retry);

        assertGrantedOnce(user, chargeId, 10_000);
        assertPaidBy(chargeId, retry.paymentId());
        assertThat(balance(user)).isEqualTo(10_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("C-04 PG 응답을 잃으면 202 UNKNOWN, 지급 없음. 재확인 작업이 승인을 확인하면 그때 한 번 지급")
    void 결과를_모르면_지급하지_않고_재확인_뒤_지급한다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 50_000);
        Prepared p = prepareOk(user, chargeId);
        fakePg.willLoseResponse(p.pgOrderId());

        MvcResult unknown = confirm(user, p);

        assertThat(unknown.getResponse().getStatus()).as(contentOf(unknown)).isEqualTo(202);
        assertThat((String) JsonPath.read(contentOf(unknown), "$.status")).isEqualTo("UNKNOWN");
        assertNotGranted(user, chargeId);

        ageForReconcile(p.paymentId());
        reconciler.runOnce();
        reconciler.runOnce();

        assertThat(paymentStatus(p.paymentId())).isEqualTo("APPROVED");
        assertGrantedOnce(user, chargeId, 50_000);
        assertPaidBy(chargeId, p.paymentId());
        assertThat(balance(user)).isEqualTo(50_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("C-05 결과 불명확인 동안 새 결제는 409 PAYMENT_UNDER_REVIEW. 재확인 작업이 실패로 확정하면 지급 없이 다시 결제할 수 있다")
    void 불명확_뒤_실패로_확정되면_지급하지_않는다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        Prepared p = prepareOk(user, chargeId);
        fakePg.willTimeout(p.pgOrderId());

        assertThat(confirm(user, p).getResponse().getStatus()).isEqualTo(202);
        Prepared during = prepare(user, chargeId, UUID.randomUUID().toString());
        assertThat(during.status()).as(during.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(during.body(), "$.code")).isEqualTo("PAYMENT_UNDER_REVIEW");

        ageForReconcile(p.paymentId());
        reconciler.runOnce();

        assertThat(paymentStatus(p.paymentId())).isEqualTo("FAILED");
        assertNotGranted(user, chargeId);
        assertThat(balance(user)).isZero();

        Prepared retry = prepareOk(user, chargeId);
        fakePg.willApprove(retry.pgOrderId());
        confirm(user, retry);

        assertGrantedOnce(user, chargeId, 10_000);
        assertPaidBy(chargeId, retry.paymentId());
        f.assertInvariants();
    }

    // ---- 결제 준비 거절 ----

    @Test
    @DisplayName("C-07 충전 주문의 결제 기한(30분)이 지나면 결제 준비가 409 PAYMENT_WINDOW_EXPIRED, 결제 행 없음")
    void 기한이_지난_충전_주문은_결제할_수_없다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = newCharge(user, 10_000);
        setChargeExpiresAt(chargeId, Instant.now().minusSeconds(1));

        Prepared p = prepare(user, chargeId, UUID.randomUUID().toString());

        assertThat(p.status()).as(p.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(p.body(), "$.code")).isEqualTo("PAYMENT_WINDOW_EXPIRED");
        assertThat(paymentCount(chargeId)).isZero();
        assertNotGranted(user, chargeId);
    }

    @Test
    @DisplayName("C-09 이미 충전된 주문으로 다시 결제 준비하면 409 PAYMENT_NOT_PAYABLE, 추가 지급 없음")
    void 충전된_주문은_다시_결제할_수_없다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);

        Prepared again = prepare(user, chargeId, UUID.randomUUID().toString());

        assertThat(again.status()).as(again.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(again.body(), "$.code")).isEqualTo("PAYMENT_NOT_PAYABLE");
        assertThat(paymentCount(chargeId)).isEqualTo(1);
        assertGrantedOnce(user, chargeId, 10_000);
    }

    @Test
    @DisplayName("C-10 다른 회원의 충전 주문으로 결제 준비하면 404 PAYMENT_TARGET_NOT_FOUND, 결제 행 없음")
    void 다른_회원의_충전_주문은_결제할_수_없다() throws Exception {
        Long owner = f.user("USER");
        Long other = f.user("USER");
        Long chargeId = newCharge(owner, 10_000);

        Prepared p = prepare(other, chargeId, UUID.randomUUID().toString());

        assertThat(p.status()).as(p.body()).isEqualTo(404);
        assertThat(JsonPath.<String>read(p.body(), "$.code")).isEqualTo("PAYMENT_TARGET_NOT_FOUND");
        assertThat(paymentCount(chargeId)).isZero();
        assertNotGranted(owner, chargeId);
    }

    @Test
    @DisplayName("없는 충전 주문으로 결제 준비하면 409 PAYMENT_NOT_PAYABLE")
    void 없는_충전_주문은_결제할_수_없다() throws Exception {
        Long user = f.user("USER");

        Prepared p = prepare(user, Long.MAX_VALUE, UUID.randomUUID().toString());

        assertThat(p.status()).as(p.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(p.body(), "$.code")).isEqualTo("PAYMENT_NOT_PAYABLE");
    }

    // ---- 환불 ----

    @Test
    @DisplayName("C-13 충전 결제의 손님 환불 요청은 409 PAYMENT_NOT_REFUNDABLE(REFUND_NOT_SUPPORTED), 지갑·결제 그대로")
    void 충전_크레딧은_환불할_수_없다() throws Exception {
        Long user = f.user("USER");
        Long chargeId = paidCharge(user, 10_000);
        Long paymentId = paidPaymentId(chargeId);

        MvcResult res = mockMvc.perform(post("/api/payments/{paymentId}/refunds", paymentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", "refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"단순 변심\"}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(409);
        assertThat(codeOf(res)).isEqualTo("PAYMENT_NOT_REFUNDABLE");
        assertThat(JsonPath.<Map<String, Object>>read(contentOf(res), "$.details"))
                .containsEntry("reason", "REFUND_NOT_SUPPORTED");
        assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED");
        assertThat(balance(user)).isEqualTo(10_000);
        assertGrantedOnce(user, chargeId, 10_000);
        f.assertInvariants();
    }

    @Test
    @DisplayName("테스트 지급 버튼은 충전 기능과 함께 그대로 동작한다")
    void 테스트_지급_버튼은_유지된다() throws Exception {
        Long user = f.user("USER");
        paidCharge(user, 10_000);

        mockMvc.perform(post("/api/me/wallet/grants").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(20_000));
        f.assertInvariants();
    }
}
