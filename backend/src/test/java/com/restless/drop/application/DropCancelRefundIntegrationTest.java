package com.restless.drop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 드롭 삭제와 00:30 환불 배치 (#430). 배치는 스케줄러를 끄고 직접 부른다. */
@SpringBootTest(properties = {"restless.ratelimit.enabled=false", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.required=false",
        "restless.drop.queue.scheduler.enabled=false", "restless.drop.cancel-refund.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("드롭 삭제와 새벽 환불 (#430)")
class DropCancelRefundIntegrationTest {
    private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DropCancelRefundJob job;

    private Long organizer;
    private Long buyer;
    private Long dropId;
    private Long variantId;

    @BeforeEach
    void seed() throws Exception {
        organizer = member("ORGANIZER");
        buyer = member("USER");
        Long concert = concert(organizer);
        Instant now = Instant.now();
        String json = """
                {"concertId":%d,"title":"삭제 드롭","description":"설명","imageUrl":"https://example.com/a.png",
                 "opensAt":"%s","closesAt":"%s",
                 "variants":[{"skuCode":"M","optionName":"M","priceAmount":39000,"totalQuantity":5}]}"""
                .formatted(concert, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofHours(2)));
        String created = mvc.perform(as(post("/api/organizer/drops"), organizer, "ORGANIZER")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        dropId = JsonPath.<Number>read(created, "$.dropId").longValue();
        mvc.perform(as(post("/api/organizer/drops/" + dropId + "/publish"), organizer, "ORGANIZER"))
                .andExpect(status().isOk());
        variantId = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, dropId);
    }

    @Test
    @DisplayName("판매 중 삭제해도 결제 완료 건은 바로 환불되지 않고, 배치가 한 번만 환불한다")
    void paidPurchaseIsRefundedOnceByNightlyJob() throws Exception {
        Long reservation = reserve(buyer);
        Long payment = pay(buyer, reservation);
        assertThat(orderStatus(reservation)).isEqualTo("PAID");

        delete(organizer, "ORGANIZER").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(orderStatus(reservation)).as("삭제 즉시 환불하지 않는다").isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM drops WHERE id = ?", Timestamp.class, dropId)).isNotNull();

        // The DB is shared with other tests, so only this purchase is checked, not the job total.
        assertThat(job.runUntil(FAR_FUTURE)).isPositive();

        assertThat(orderStatus(reservation)).isEqualTo("REFUNDED");
        assertThat(reservationStatus(reservation)).isEqualTo("RESTORED");
        assertThat(refundCount(payment)).isEqualTo(1);

        job.runUntil(FAR_FUTURE);
        assertThat(refundCount(payment)).as("환불된 점유는 다시 고르지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("08:00이 지나면 배치는 멈추고 남은 건은 그대로 둔다")
    void jobStopsAtWindowEnd() throws Exception {
        Long reservation = reserve(buyer);
        Long payment = pay(buyer, reservation);
        delete(organizer, "ORGANIZER").andExpect(status().isOk());

        assertThat(job.runUntil(Instant.now().minusSeconds(1))).isZero();

        assertThat(orderStatus(reservation)).isEqualTo("PAID");
        assertThat(refundCount(payment)).isZero();
    }

    @Test
    @DisplayName("삭제 뒤에는 결제를 시작할 수 없다. 돈이 나가지 않는다")
    void paymentCannotStartAfterDelete() throws Exception {
        Long reservation = reserve(buyer);
        String prepared = prepare(buyer, reservation);
        delete(organizer, "ORGANIZER").andExpect(status().isOk());

        int confirm = confirm(buyer, prepared);

        assertThat(confirm).isNotEqualTo(200);
        assertThat(reservationStatus(reservation)).isNotEqualTo("CONFIRMED");
        Long payment = JsonPath.<Number>read(prepared, "$.paymentId").longValue();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                .isNotEqualTo("APPROVED");
    }

    @Test
    @DisplayName("다른 주최자는 삭제할 수 없고, 이미 삭제한 드롭은 409다. 관리자는 삭제할 수 있다")
    void onlyOwnerOrAdminDeletes() throws Exception {
        Long other = member("ORGANIZER");
        delete(other, "ORGANIZER").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM drops WHERE id = ?", String.class, dropId)).isEqualTo("SCHEDULED");

        delete(member("ADMIN"), "ADMIN").andExpect(status().isOk());
        delete(organizer, "ORGANIZER").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DROP_INVALID_STATE"));
    }

    @Test
    @DisplayName("삭제된 드롭은 새로 점유할 수 없다")
    void cannotReserveAfterDelete() throws Exception {
        delete(organizer, "ORGANIZER").andExpect(status().isOk());
        int code = mvc.perform(as(post("/api/drops/" + dropId + "/reservations"), buyer, "USER")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reservationJson()))
                .andReturn().getResponse().getStatus();
        assertThat(code).isNotEqualTo(201);
    }

    private org.springframework.test.web.servlet.ResultActions delete(Long actor, String role) throws Exception {
        return mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/organizer/drops/" + dropId), actor, role));
    }

    private Long reserve(Long user) throws Exception {
        String body = mvc.perform(as(post("/api/drops/" + dropId + "/reservations"), user, "USER")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reservationJson()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.reservationId").longValue();
    }

    private String reservationJson() {
        return """
                {"variantId":%d,"recipient":"회원","phone":"010","address":"서울"}""".formatted(variantId);
    }

    private String prepare(Long user, Long reservation) throws Exception {
        return mvc.perform(as(post("/api/payments"), user, "USER")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payableType\":\"DROP\",\"payableId\":" + reservation + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private int confirm(Long user, String prepared) throws Exception {
        long paymentId = JsonPath.<Number>read(prepared, "$.paymentId").longValue();
        String orderId = JsonPath.read(prepared, "$.pgOrderId");
        long amount = JsonPath.<Number>read(prepared, "$.amount").longValue();
        return mvc.perform(as(post("/api/payments/confirm"), user, "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"pk-%d\",\"orderId\":\"%s\",\"amount\":%d}".formatted(paymentId, orderId, amount)))
                .andReturn().getResponse().getStatus();
    }

    private Long pay(Long user, Long reservation) throws Exception {
        String prepared = prepare(user, reservation);
        assertThat(confirm(user, prepared)).isEqualTo(200);
        return JsonPath.<Number>read(prepared, "$.paymentId").longValue();
    }

    private String orderStatus(Long reservation) {
        return jdbc.queryForObject("SELECT o.status FROM orders o JOIN drop_reservations r ON r.order_id = o.id WHERE r.id = ?",
                String.class, reservation);
    }

    private String reservationStatus(Long reservation) {
        return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, reservation);
    }

    private Integer refundCount(Long payment) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_refunds WHERE payment_id = ?", Integer.class, payment);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Long actor, String role) {
        return request.with(jwt().jwt(token -> token.subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private Long member(String role) {
        String email = UUID.randomUUID() + "@cancel-refund.test";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'cancel-refund', ?, 'ACTIVE', ?, ?)""", email, role, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long concert(Long seller) {
        String unique = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                unique, now);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, unique);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, seller, unique, now);
        return jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, unique);
    }
}
