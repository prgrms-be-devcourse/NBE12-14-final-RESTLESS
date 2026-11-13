package com.restless.drop.acceptance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 드롭 탈퇴 제한 수용 테스트 (#41). 기준: {@code docs/contracts/api/drop.md} "회원 탈퇴 제한", policy.md 11절.
 * 재고를 점유 중이거나 돈의 행방이 정해지지 않은 드롭 거래가 있으면 탈퇴는 409 {@code MEMBER_HAS_ACTIVE_PARTICIPATION}이다.
 * 각 테스트는 "막힘"과 "풀림"을 함께 본다. 너무 적게 막는 구현과 너무 많이 막는 구현을 모두 잡기 위해서다.
 * <b>하네스 파일이다.</b> #41 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@DisplayName("드롭 탈퇴 제한")
class DropWithdrawalAcceptanceTest extends DropAcceptanceSupport {

    private static final String PASSWORD = "drop-withdraw-1234";

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("재고 점유와 결제 결과 대기(RESERVED, CONFIRMING, RECONCILING, COMPENSATING) 중에는 탈퇴할 수 없고, 점유가 만료되면 탈퇴할 수 있다")
    void openStockHoldBlocksUntilItEnds() {
        Long user = withdrawableUser();
        DropFixture drop = onSaleDrop(3);
        Hold hold = holdFor(user, drop);

        for (String status : List.of("RESERVED", "CONFIRMING", "RECONCILING", "COMPENSATING")) {
            jdbc.update("UPDATE drop_reservations SET status = ? WHERE id = ?", status, hold.reservationId());
            assertBlocked(user, "재고 점유 " + status);
        }

        // 기한이 지나 점유를 반환하고 주문을 취소한 상태 (재고 보존식 유지: reserved -> available).
        jdbc.update("UPDATE drop_reservations SET status = 'EXPIRED' WHERE id = ?", hold.reservationId());
        jdbc.update("UPDATE drop_inventories SET reserved_quantity = reserved_quantity - 1, available_quantity = available_quantity + 1 WHERE variant_id = ?",
                drop.variantIds().get(0));
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", hold.orderId());
        assertWithdraws(user);
    }

    @Test
    @DisplayName("결제는 끝났지만 돈의 행방이 정해지지 않은 주문(PAYMENT_REVIEW, REFUND_PENDING, REFUND_REVIEW)이 있으면 탈퇴할 수 없고, PAID 주문은 탈퇴를 막지 않는다")
    void undecidedMoneyBlocksButPaidDoesNot() {
        Long user = withdrawableUser();
        DropFixture drop = onSaleDrop(3);
        Hold hold = holdFor(user, drop);
        confirmPurchase(hold, drop);

        for (String status : List.of("PAYMENT_REVIEW", "REFUND_PENDING", "REFUND_REVIEW")) {
            jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status, hold.orderId());
            assertBlocked(user, "주문 " + status);
        }

        jdbc.update("UPDATE orders SET status = 'PAID' WHERE id = ?", hold.orderId());
        assertWithdraws(user);
    }

    @Test
    @DisplayName("다른 회원의 진행 중인 드롭 거래는 내 탈퇴를 막지 않는다")
    void onlyMyOwnPurchasesCount() {
        Long holder = withdrawableUser();
        Long other = withdrawableUser();
        holdFor(holder, onSaleDrop(3));

        assertBlocked(holder, "재고 점유 RESERVED");
        assertWithdraws(other);
    }

    // ---- 도우미 ----

    record Hold(Long reservationId, Long orderId) {}

    /** 실제 비밀번호를 가진 회원. 탈퇴 API는 비밀번호를 다시 확인한다. */
    private Long withdrawableUser() {
        Long user = newUser();
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), user);
        return user;
    }

    private Hold holdFor(Long user, DropFixture drop) {
        Response r = reserve(user, drop, UUID.randomUUID().toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return jdbc.queryForObject("SELECT id, order_id FROM drop_reservations WHERE user_id = ? AND drop_id = ?",
                (rs, i) -> new Hold(rs.getLong(1), rs.getLong(2)), user, drop.dropId());
    }

    /** 결제 승인으로 구매가 확정된 상태 (재고 보존식 유지: reserved -> sold). */
    private void confirmPurchase(Hold hold, DropFixture drop) {
        jdbc.update("UPDATE drop_reservations SET status = 'CONFIRMED' WHERE id = ?", hold.reservationId());
        jdbc.update("UPDATE drop_inventories SET reserved_quantity = reserved_quantity - 1, sold_quantity = sold_quantity + 1 WHERE variant_id = ?",
                drop.variantIds().get(0));
        jdbc.update("UPDATE orders SET status = 'PAID' WHERE id = ?", hold.orderId());
    }

    private Response withdraw(Long user) {
        return post("/api/me/withdraw", user, "{\"password\":\"" + PASSWORD + "\"}");
    }

    private void assertBlocked(Long user, String situation) {
        Response r = withdraw(user);
        assertThat(r.status()).as(situation + ": " + r.body()).isEqualTo(409);
        assertThat(r.code()).as(situation).isEqualTo("MEMBER_HAS_ACTIVE_PARTICIPATION");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user)).isEqualTo("ACTIVE");
    }

    private void assertWithdraws(Long user) {
        Response r = withdraw(user);
        assertThat(r.status()).as(r.body()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user)).isEqualTo("WITHDRAWN");
    }
}
