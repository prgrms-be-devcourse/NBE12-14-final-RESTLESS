package com.restless.drop.acceptance;

import com.restless.payment.application.PaymentReconciler;
import com.restless.payment.infrastructure.fake.FakePgClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 결제 연결 수용 테스트. 기준: {@code docs/contracts/api/drop.md} D3, {@code docs/domains/drop/transactions.md} TX2~TX5,
 * {@code policy.md} 8·10절, 불변식 INV-09·10·11·12·15.
 * <b>하네스 파일이다.</b> D3 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 *
 * <p>결제는 공통 결제 API와 Fake PG로 끝까지 실행한다. 시간 의존 작업은 계약에 정한 빈 이름으로 직접 실행한다.
 */
@Disabled("D3 구현 전. D3 구현 PR에서 이 줄만 지운다")
@DisplayName("D3 결제 연결")
@TestPropertySource(properties = {
        "restless.payment.reconcile.processing-stale-after=1ms",
        "restless.payment.reconcile.unknown-retry-after=1ms"
})
class DropPaymentAcceptanceTest extends DropAcceptanceSupport {
    @Autowired FakePgClient fakePg;
    @Autowired PaymentReconciler reconciler;
    @Autowired ApplicationContext context;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    @Test
    @DisplayName("결제 성공 → 재고 점유 CONFIRMED, 점유 -1 판매 +1, 주문 PAID. 같은 회원이 다시 살 수 있다 (INV-09)")
    void approvedPaymentConfirmsPurchase() {
        DropFixture drop = onSaleDrop(2);
        Long user = newUser();
        Hold hold = hold(user, drop);

        Payment p = prepare(user, hold);
        assertThat(p.amount()).isEqualTo(39000L);
        Response confirmed = confirm(user, p);

        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(reservationStatus(hold)).isEqualTo("CONFIRMED");
        assertThat(orderStatus(hold)).isEqualTo("PAID");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 2, "available", 1, "reserved", 0, "sold", 1));
        assertThat(reserve(user, drop, "second-purchase").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("결제 실패가 기한 전에 확정되면 재고 점유를 유지하고, 새 결제로 다시 결제할 수 있다 (policy 8절)")
    void failureBeforeDeadlineKeepsHoldForRetry() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);

        Payment first = prepare(user, hold);
        fakePg.willReject(first.pgOrderId(), "REJECT_CARD_COMPANY");
        Response failed = confirm(user, first);

        assertThat(failed.status()).as(failed.body()).isEqualTo(409);
        assertThat(failed.code()).isEqualTo("PAYMENT_FAILED");
        assertThat(reservationStatus(hold)).isEqualTo("RESERVED");
        assertThat(orderStatus(hold)).isEqualTo("PENDING_PAYMENT");
        assertThat(inventory(drop.variant())).containsEntry("reserved", 1).containsEntry("available", 0);

        Payment retry = prepare(user, hold);
        assertThat(retry.paymentId()).isNotEqualTo(first.paymentId());
        assertThat(confirm(user, retry).status()).isEqualTo(200);
        assertThat(reservationStatus(hold)).isEqualTo("CONFIRMED");
        assertThat(inventory(drop.variant())).containsEntry("sold", 1).containsEntry("reserved", 0);
    }

    @Test
    @DisplayName("결제 실패가 기한 뒤에 확정되면 재고를 반환하고 주문을 취소한다")
    void failureAfterDeadlineReleasesStock() throws Exception {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        Payment p = prepare(user, hold);
        fakePg.willDelay(p.pgOrderId(), Duration.ofSeconds(3));
        fakePg.willReject(p.pgOrderId(), "REJECT_CARD_COMPANY");

        CompletableFuture<Response> confirming = CompletableFuture.supplyAsync(() -> confirm(user, p));
        Thread.sleep(1500); // 승인 시작은 기한 전에 받아들여졌고, PG 응답을 기다리는 동안 기한이 지난다
        jdbc.update("UPDATE drop_reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), hold.reservationId());
        Response failed = confirming.get();

        assertThat(failed.code()).as(failed.body()).isEqualTo("PAYMENT_FAILED");
        assertThat(reservationStatus(hold)).isEqualTo("RELEASED");
        assertThat(orderStatus(hold)).isEqualTo("CANCELLED");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 1, "available", 1, "reserved", 0, "sold", 0));
    }

    @Test
    @DisplayName("결제 결과를 모르면 재고를 돌려주지 않고, 만료 작업도 건드리지 않는다. 대사가 승인을 확인하면 구매가 확정된다 (INV-10)")
    void unknownResultProtectsStockUntilReconciled() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        Payment p = prepare(user, hold);
        fakePg.willLoseResponse(p.pgOrderId()); // PG는 승인했지만 응답이 유실됨

        Response unknown = confirm(user, p);

        assertThat(unknown.status()).as(unknown.body()).isEqualTo(202);
        assertThat(reservationStatus(hold)).isEqualTo("RECONCILING");
        assertThat(orderStatus(hold)).isEqualTo("PAYMENT_REVIEW");
        jdbc.update("UPDATE drop_reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), hold.reservationId());
        runJob("dropReservationExpiryJob");
        assertThat(reservationStatus(hold)).isEqualTo("RECONCILING");
        assertThat(inventory(drop.variant())).containsEntry("reserved", 1);

        reconciler.runOnce();

        assertThat(reservationStatus(hold)).isEqualTo("CONFIRMED");
        assertThat(orderStatus(hold)).isEqualTo("PAID");
        assertThat(inventory(drop.variant())).containsEntry("sold", 1).containsEntry("reserved", 0);
    }

    @Test
    @DisplayName("결제를 시작하지 않고 기한이 지나면 만료 작업이 재고를 반환하고 주문을 취소하며, 같은 회원이 다시 점유할 수 있다")
    void expiryJobReleasesUnpaidHold() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        jdbc.update("UPDATE drop_reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), hold.reservationId());

        runJob("dropReservationExpiryJob");

        assertThat(reservationStatus(hold)).isEqualTo("EXPIRED");
        assertThat(orderStatus(hold)).isEqualTo("CANCELLED");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 1, "available", 1, "reserved", 0, "sold", 0));
        assertThat(reserve(user, drop, "after-expiry").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("기한 직전의 결제 시작과 만료 작업이 동시에 일어나도 둘 중 하나만 반영된다")
    void approvalStartRacesExpiryWithOneOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            DropFixture drop = onSaleDrop(1);
            Long user = newUser();
            Hold hold = hold(user, drop);
            Payment p = prepare(user, hold);
            jdbc.update("UPDATE drop_reservations SET expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().plusMillis(300)), hold.reservationId());
            Thread.sleep(250);

            List<Callable<Object>> race = List.of(() -> confirm(user, p), () -> {
                Thread.sleep(50);
                runJob("dropReservationExpiryJob");
                return null;
            });
            concurrently(2, race);
            runJob("dropReservationExpiryJob");

            String status = reservationStatus(hold);
            assertThat(status).as("round %d", round).isIn("CONFIRMED", "EXPIRED");
            if (status.equals("CONFIRMED")) {
                assertThat(orderStatus(hold)).isEqualTo("PAID");
                assertThat(inventory(drop.variant())).containsEntry("sold", 1);
            } else {
                assertThat(orderStatus(hold)).isEqualTo("CANCELLED");
                assertThat(inventory(drop.variant())).containsEntry("available", 1);
                assertThat(paymentStatus(p)).isNotEqualTo("APPROVED");
            }
        }
    }

    @Test
    @DisplayName("이전 결제 시도로는 현재 재고 점유를 바꾸지 못한다. 새 시도를 만들면 이전 시도의 승인 요청은 PG를 부르지 않고 실패한다 (INV-15)")
    void staleAttemptCannotChangeHold() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        Payment old = prepare(user, hold);
        Payment current = prepare(user, hold);

        Response stale = confirm(user, old);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(reservationStatus(hold)).isEqualTo("RESERVED");
        assertThat(confirm(user, current).status()).isEqualTo(200);
        assertThat(reservationStatus(hold)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("같은 결제 승인 요청이 동시에 5번 와도 판매는 한 번만 반영된다 (INV-11)")
    void duplicateConfirmationsApplyOnce() throws Exception {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        Payment p = prepare(user, hold);

        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) tasks.add(() -> confirm(user, p));
        concurrently(5, tasks);

        assertThat(reservationStatus(hold)).isEqualTo("CONFIRMED");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 1, "available", 0, "reserved", 0, "sold", 1));
    }

    @Test
    @DisplayName("손님 전액 환불은 PG 취소 확인 뒤 판매 -1, 구매 가능 +1을 정확히 한 번 반영한다 (INV-12)")
    void customerRefundRestoresStockOnce() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);
        Payment p = prepare(user, hold);
        confirm(user, p);

        Response refunded = refund(user, p, "refund-1");
        Response replay = refund(user, p, "refund-1");

        assertThat(refunded.status()).as(refunded.body()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(reservationStatus(hold)).isEqualTo("RESTORED");
        assertThat(orderStatus(hold)).isEqualTo("REFUNDED");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 1, "available", 1, "reserved", 0, "sold", 0));
        assertThat(refund(user, p, "refund-2").status()).isEqualTo(409);
        assertThat(inventory(drop.variant())).containsEntry("available", 1);
    }

    @Test
    @DisplayName("결제 승인을 시작하기 전이면 본인이 재고 점유를 취소할 수 있고, 시작한 뒤에는 409다")
    void cancelBeforeApprovalOnly() {
        DropFixture drop = onSaleDrop(2);
        Long user = newUser();
        Hold hold = hold(user, drop);

        assertThat(cancel(newUser(), hold).status()).isEqualTo(404);
        Response cancelled = cancel(user, hold);
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(reservationStatus(hold)).isEqualTo("RELEASED");
        assertThat(orderStatus(hold)).isEqualTo("CANCELLED");
        assertThat(inventory(drop.variant())).containsEntry("available", 2).containsEntry("reserved", 0);

        Hold second = hold(user, drop);
        Payment p = prepare(user, second);
        fakePg.willTimeout(p.pgOrderId());
        confirm(user, p);
        Response tooLate = cancel(user, second);
        assertThat(tooLate.status()).isEqualTo(409);
        assertThat(tooLate.code()).isEqualTo("DROP_INVALID_STATE");
    }

    @Test
    @DisplayName("결제 준비는 본인 재고 점유만, 금액은 서버 주문 금액이다")
    void paymentPreparationUsesServerAmountAndOwner() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Hold hold = hold(user, drop);

        Response other = preparePayment(newUser(), hold.reservationId(), UUID.randomUUID().toString());
        assertThat(other.status()).isEqualTo(404);
        assertThat(prepare(user, hold).amount()).isEqualTo(39000L);
    }

    // ---- helpers ----

    record Hold(Long reservationId, Long orderId) { }

    record Payment(Long paymentId, String pgOrderId, long amount) { }

    Hold hold(Long user, DropFixture drop) {
        Response r = reserve(user, drop, UUID.randomUUID().toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new Hold(r.longAt("$.reservationId"), r.longAt("$.orderId"));
    }

    Response preparePayment(Long user, Long reservationId, String key) {
        return send(HttpRequest.newBuilder(uri("/api/payments"))
                .header("Authorization", "Bearer " + token(user))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"payableType\":\"DROP\",\"payableId\":" + reservationId + "}")));
    }

    Payment prepare(Long user, Hold hold) {
        Response r = preparePayment(user, hold.reservationId(), UUID.randomUUID().toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new Payment(r.longAt("$.paymentId"), r.string("$.pgOrderId"), r.longAt("$.amount"));
    }

    Response confirm(Long user, Payment p) {
        return post("/api/payments/confirm", user,
                "{\"paymentKey\":\"pk-" + p.paymentId() + "\",\"orderId\":\"" + p.pgOrderId() + "\",\"amount\":" + p.amount() + "}");
    }

    Response refund(Long user, Payment p, String key) {
        return send(HttpRequest.newBuilder(uri("/api/payments/" + p.paymentId() + "/refunds"))
                .header("Authorization", "Bearer " + token(user))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"단순 변심\"}")));
    }

    Response cancel(Long user, Hold hold) {
        Long dropId = jdbc.queryForObject("SELECT drop_id FROM drop_reservations WHERE id = ?", Long.class, hold.reservationId());
        return post("/api/drops/" + dropId + "/reservations/" + hold.reservationId() + "/cancel", user, "");
    }

    String reservationStatus(Hold hold) {
        return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, hold.reservationId());
    }

    String orderStatus(Hold hold) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, hold.orderId());
    }

    String paymentStatus(Payment p) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, p.paymentId());
    }

    /** 계약(drop.md)에 정한 작업 빈을 구현 클래스 import 없이 실행한다. */
    void runJob(String beanName) {
        Object job = context.getBean(beanName);
        try {
            job.getClass().getMethod("runOnce").invoke(job);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(beanName + ".runOnce() 실행 실패", e);
        }
    }
}
