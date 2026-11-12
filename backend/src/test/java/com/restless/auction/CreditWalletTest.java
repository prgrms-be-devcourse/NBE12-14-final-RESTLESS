package com.restless.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restless.auction.domain.CreditHold;
import com.restless.auction.domain.CreditHoldStatus;
import com.restless.auction.domain.CreditWallet;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.lang.reflect.Constructor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CreditWalletTest {
    static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

    static CreditWallet wallet(long balance) throws Exception {
        Constructor<CreditWallet> c = CreditWallet.class.getDeclaredConstructor();
        c.setAccessible(true);
        CreditWallet wallet = c.newInstance();
        if (balance > 0) wallet.grant(balance, NOW);
        return wallet;
    }

    @Test void 정책_예시대로_보유_묶임_사용가능이_바뀐다() throws Exception {
        CreditWallet w = wallet(100_000);
        w.reserve(60_000, NOW);                       // A 경매 입찰
        w.reserve(30_000, NOW);                       // B 경매 입찰
        assertThat(w.availableAmount()).isEqualTo(10_000);
        w.release(60_000, NOW);                       // A 추월당함
        assertThat(w.availableAmount()).isEqualTo(70_000);
        w.capture(30_000, NOW);                       // B 낙찰
        assertThat(w.getBalanceAmount()).isEqualTo(70_000);
        assertThat(w.getReservedAmount()).isZero();
        assertThat(w.availableAmount()).isEqualTo(70_000);
    }

    @Test void 사용가능액보다_많이_묶으면_CREDIT_INSUFFICIENT_INV_AU_06() throws Exception {
        CreditWallet w = wallet(10_000);
        w.reserve(6_000, NOW);
        assertThatThrownBy(() -> w.reserve(4_001, NOW))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CREDIT_INSUFFICIENT);
        assertThat(w.getReservedAmount()).isEqualTo(6_000);
    }

    @Test void 묶인_금액보다_많이_풀거나_차감할_수_없다() throws Exception {
        CreditWallet w = wallet(10_000);
        w.reserve(1_000, NOW);
        assertThatThrownBy(() -> w.release(1_001, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> w.capture(1_001, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test void 금액은_양수만() throws Exception {
        CreditWallet w = wallet(10_000);
        assertThatThrownBy(() -> w.grant(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.reserve(-1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 확보는_HELD에서만_해제나_차감된다() {
        CreditHold released = CreditHold.held(1L, 1L, 1_000, NOW);
        released.release(NOW);
        assertThat(released.getStatus()).isEqualTo(CreditHoldStatus.RELEASED);
        assertThat(released.getReleasedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> released.capture(NOW)).isInstanceOf(IllegalStateException.class);

        CreditHold captured = CreditHold.held(1L, 2L, 1_000, NOW);
        captured.capture(NOW);
        assertThat(captured.getStatus()).isEqualTo(CreditHoldStatus.CAPTURED);
        assertThatThrownBy(() -> captured.release(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
