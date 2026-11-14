package com.restless.drop.acceptance;

import com.restless.payment.infrastructure.fake.FakePgClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.net.http.HttpRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 상세 수용 테스트 (#109). 기준: {@code docs/contracts/api/common.md} "주문 상세".
 * 상세가 주는 결제 대상과 최근 결제만으로 재결제와 환불을 끝까지 할 수 있는지 본다. 결제는 Fake PG로 실행한다.
 * <b>하네스 파일이다.</b> #109 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@Disabled("#109 구현 PR에서 이 줄만 지운다")
@DisplayName("주문 상세")
class OrderDetailAcceptanceTest extends DropAcceptanceSupport {

    @Autowired FakePgClient fakePg;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    @Test
    @DisplayName("드롭 주문 상세의 결제 대상으로 재결제하고, 최근 결제 ID로 환불할 수 있다. 결제 시도가 바뀌면 최근 시도가 나온다")
    void dropOrderDetailDrivesRetryAndRefund() {
        Long user = newUser();
        DropFixture drop = onSaleDrop(1);
        Response hold = reserve(user, drop, UUID.randomUUID().toString());
        assertThat(hold.status()).as(hold.body()).isEqualTo(201);
        Long orderId = hold.longAt("$.orderId");
        Long reservationId = hold.longAt("$.reservationId");

        Response before = detail(user, orderId);
        assertThat(before.status()).as(before.body()).isEqualTo(200);
        assertThat(number(before, "$.orderId")).isEqualTo(orderId);
        assertThat(before.string("$.orderType")).isEqualTo("DROP");
        assertThat(before.string("$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(number(before, "$.totalAmount")).isEqualTo(39000);
        assertThat(Instant.parse(before.string("$.createdAt"))).isNotNull();
        assertThat(before.string("$.lines[0].itemName")).isEqualTo("후드티 드롭");
        assertThat(before.string("$.lines[0].optionName")).isEqualTo("M");
        assertThat(before.string("$.shipping.recipient")).isEqualTo("홍길동");
        assertThat(before.string("$.shipping.phone")).isEqualTo("010-1234-5678");
        assertThat(before.string("$.shipping.address")).isEqualTo("서울시 강남구 1");
        assertThat(before.string("$.payable.payableType")).isEqualTo("DROP");
        assertThat(number(before, "$.payable.payableId")).isEqualTo(reservationId);
        // DB는 마이크로초까지 저장한다. 점유 응답은 저장 전 시각이라 시계에 따라 나노초가 붙는다 (Linux CI).
        assertThat(Instant.parse(before.string("$.payableExpiresAt")).truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(Instant.parse(hold.string("$.expiresAt")).truncatedTo(ChronoUnit.MICROS));
        assertThat(before.<Object>read("$.payment")).isNull();

        // 상세가 준 결제 대상 그대로 결제를 준비한다. 첫 시도는 카드사 거절로 실패한다.
        Payment first = prepare(user, before.string("$.payable.payableType"), number(before, "$.payable.payableId"));
        Response afterPrepare = detail(user, orderId);
        assertThat(number(afterPrepare, "$.payment.paymentId")).isEqualTo(first.paymentId());
        assertThat(afterPrepare.string("$.payment.status")).isEqualTo(paymentStatus(first.paymentId()));

        fakePg.willReject(first.pgOrderId(), "REJECT_CARD_COMPANY");
        assertThat(confirm(user, first).status()).isEqualTo(409);
        Response afterFailure = detail(user, orderId);
        assertThat(afterFailure.string("$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(afterFailure.string("$.payment.status")).isEqualTo("FAILED");

        Payment retry = prepare(user, afterFailure.string("$.payable.payableType"), number(afterFailure, "$.payable.payableId"));
        assertThat(retry.paymentId()).isNotEqualTo(first.paymentId());
        Response confirmed = confirm(user, retry);
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);

        Response paid = detail(user, orderId);
        assertThat(paid.string("$.status")).isEqualTo("PAID");
        assertThat(number(paid, "$.payment.paymentId")).isEqualTo(retry.paymentId());
        assertThat(paid.string("$.payment.status")).isEqualTo("APPROVED");

        // 상세가 준 최근 결제 ID로 환불한다.
        Response refunded = refund(user, number(paid, "$.payment.paymentId"));
        assertThat(refunded.status()).as(refunded.body()).isEqualTo(200);
        Response after = detail(user, orderId);
        assertThat(after.string("$.status")).isEqualTo("REFUNDED");
        assertThat(number(after, "$.payment.paymentId")).isEqualTo(retry.paymentId());
        assertThat(after.string("$.payment.status")).isEqualTo(paymentStatus(retry.paymentId()));
    }

    @Test
    @DisplayName("결제 대상을 알려 주는 도메인이 없는 주문(티켓)은 payable, payableExpiresAt, payment, shipping이 null이다")
    void ticketOrderHasNoPayableYet() {
        Long user = newUser();
        Long orderId = ticketOrder(user);

        Response r = detail(user, orderId);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.string("$.orderType")).isEqualTo("TICKET");
        assertThat(r.string("$.status")).isEqualTo("PAID");
        assertThat(r.string("$.lines[0].itemName")).isEqualTo("RESTLESS 공연");
        assertThat(r.<Object>read("$.shipping")).isNull();
        assertThat(r.<Object>read("$.payable")).isNull();
        assertThat(r.<Object>read("$.payableExpiresAt")).isNull();
        assertThat(r.<Object>read("$.payment")).isNull();
    }

    @Test
    @DisplayName("남의 주문과 없는 주문은 404 ORDER_NOT_FOUND, 숫자가 아닌 ID는 400, 로그인하지 않으면 401, 탈퇴 회원은 404 MEMBER_NOT_FOUND다")
    void onlyTheOwnerCanSeeIt() {
        Long owner = newUser();
        Long stranger = newUser();
        Long orderId = ticketOrder(owner);

        Response theirs = detail(stranger, orderId);
        assertThat(theirs.status()).as(theirs.body()).isEqualTo(404);
        assertThat(theirs.code()).isEqualTo("ORDER_NOT_FOUND");

        Response missing = get("/api/orders/" + (orderId + 1_000_000), owner);
        assertThat(missing.status()).as(missing.body()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("ORDER_NOT_FOUND");

        Response badId = get("/api/orders/abc", owner);
        assertThat(badId.status()).as(badId.body()).isEqualTo(400);
        assertThat(badId.code()).isEqualTo("INVALID_REQUEST");

        Response anonymous = get("/api/orders/" + orderId, null);
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHORIZED");

        jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), owner);
        Response withdrawn = detail(owner, orderId);
        assertThat(withdrawn.status()).as(withdrawn.body()).isEqualTo(404);
        assertThat(withdrawn.code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    // ---- 도우미 ----

    record Payment(Long paymentId, String pgOrderId, long amount) {}

    private Response detail(Long user, Long orderId) {
        return get("/api/orders/" + orderId, user);
    }

    private Payment prepare(Long user, String payableType, long payableId) {
        Response r = send(HttpRequest.newBuilder(uri("/api/payments"))
                .header("Authorization", "Bearer " + token(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"payableType\":\"" + payableType + "\",\"payableId\":" + payableId + "}")));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new Payment(r.longAt("$.paymentId"), r.string("$.pgOrderId"), r.longAt("$.amount"));
    }

    private Response confirm(Long user, Payment p) {
        return post("/api/payments/confirm", user,
                "{\"paymentKey\":\"pk-" + p.paymentId() + "\",\"orderId\":\"" + p.pgOrderId() + "\",\"amount\":" + p.amount() + "}");
    }

    private Response refund(Long user, long paymentId) {
        return send(HttpRequest.newBuilder(uri("/api/payments/" + paymentId + "/refunds"))
                .header("Authorization", "Bearer " + token(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"단순 변심\"}")));
    }

    private String paymentStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    /** 티켓팅이 만든 것과 같은 공통 주문 행. 배송지가 없고, 결제 대상을 알려 주는 구현도 아직 없다. */
    private Long ticketOrder(Long user) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO orders (user_id, order_type, status, total_amount, created_at, updated_at)
                    VALUES (?, 'TICKET', 'PAID', 50000, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, user);
            ps.setTimestamp(2, at);
            ps.setTimestamp(3, at);
            return ps;
        }, key);
        Long orderId = key.getKey().longValue();
        jdbc.update("""
                INSERT INTO order_items (order_id, item_type, item_ref_id, item_name_snapshot, option_name_snapshot,
                                         unit_price_snapshot, quantity)
                VALUES (?, 'TICKET_SEAT', 1, 'RESTLESS 공연', 'R석 A-1', 50000, 1)""", orderId);
        return orderId;
    }

    private static long number(Response r, String path) {
        return ((Number) r.read(path)).longValue();
    }
}
