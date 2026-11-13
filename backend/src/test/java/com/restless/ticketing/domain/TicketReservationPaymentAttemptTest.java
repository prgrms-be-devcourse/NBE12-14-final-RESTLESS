package com.restless.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("예약의 승인 시작 수락")
class TicketReservationPaymentAttemptTest {
    private static final Instant CHECKOUT_AT = Instant.parse("2026-11-13T06:00:00Z");
    private static final Instant PAYMENT_DEADLINE = CHECKOUT_AT.plusSeconds(300);

    @Test
    @DisplayName("수락한 시도 문맥을 보존하고 예약 상태·최초 결제 기한은 변경하지 않는다")
    void recordsAcceptedAttemptWithoutChangingCheckout() {
        TicketReservation reservation = checkoutReservation();
        Instant attemptAt = CHECKOUT_AT.plusSeconds(10);
        Instant checkedAt = attemptAt.plusSeconds(2);

        assertThat(reservation.startApproval(9001L, attemptAt, checkedAt)).isTrue();

        assertThat(reservation.getCurrentPaymentId()).isEqualTo(9001L);
        assertThat(reservation.getApprovalStartedAt()).isEqualTo(attemptAt);
        assertThat(reservation.isPaymentResultPending()).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(TicketReservationStatus.PAYMENT_PENDING);
        assertThat(reservation.getCheckoutStartedAt()).isEqualTo(CHECKOUT_AT);
        assertThat(reservation.getPaymentExpiresAt()).isEqualTo(PAYMENT_DEADLINE);
        assertThat(reservation.matchesPaymentAttempt(9001L, attemptAt)).isTrue();
        assertThat(reservation.matchesPaymentAttempt(9002L, attemptAt)).isFalse();
        assertThat(reservation.matchesPaymentAttempt(9001L, checkedAt)).isFalse();
        assertThat(reservation.matchesPaymentAttempt(null, null)).isFalse();
    }

    @ParameterizedTest(name = "{displayName} [기한 대비 초={0}]")
    @ValueSource(longs = {-1, 0, 1})
    @DisplayName("잠금 전 시도가 기한 안이어도 잠금 후 현재 시각이 기한 정각 이상이면 거절한다")
    void checksDeadlineUsingTimeAfterLock(long secondsFromDeadline) {
        TicketReservation reservation = checkoutReservation();
        Instant attemptAt = PAYMENT_DEADLINE.minusSeconds(2);
        boolean accepted = reservation.startApproval(9001L, attemptAt, PAYMENT_DEADLINE.plusSeconds(secondsFromDeadline));
        assertThat(accepted).isEqualTo(secondsFromDeadline < 0);
        assertThat(reservation.getCurrentPaymentId()).isEqualTo(accepted ? 9001L : null);
        assertThat(reservation.getApprovalStartedAt()).isEqualTo(accepted ? attemptAt : null);
        assertThat(reservation.isPaymentResultPending()).isEqualTo(accepted);
    }

    @Test
    @DisplayName("같은 시도 재수신은 최초 문맥을 유지하고 다른 시도·같은 ID의 다른 시각은 거절한다")
    void preventsOverwritingUnresolvedAttempt() {
        TicketReservation reservation = checkoutReservation();
        Instant attemptAt = CHECKOUT_AT.plusSeconds(10);
        assertThat(reservation.startApproval(9001L, attemptAt, attemptAt)).isTrue();
        assertThat(reservation.startApproval(9001L, attemptAt, attemptAt.plusSeconds(1))).isTrue();
        assertThat(reservation.startApproval(9002L, attemptAt.plusSeconds(2), attemptAt.plusSeconds(2))).isFalse();
        assertThat(reservation.startApproval(9001L, attemptAt.plusSeconds(2), attemptAt.plusSeconds(2))).isFalse();
        assertThat(reservation.getCurrentPaymentId()).isEqualTo(9001L);
        assertThat(reservation.getApprovalStartedAt()).isEqualTo(attemptAt);
        assertThat(reservation.getPaymentExpiresAt()).isEqualTo(PAYMENT_DEADLINE);
    }

    @Test
    @DisplayName("checkout하지 않은 선점과 반환된 예약에서는 승인을 시작할 수 없다")
    void rejectsHoldAndReleasedReservation() {
        TicketReservation reservation = heldReservation();
        assertThat(reservation.startApproval(9001L, CHECKOUT_AT, CHECKOUT_AT)).isFalse();
        reservation.cancelHold();
        assertThat(reservation.startApproval(9001L, CHECKOUT_AT, CHECKOUT_AT)).isFalse();
        assertThat(reservation.getCurrentPaymentId()).isNull();
        assertThat(reservation.isPaymentResultPending()).isFalse();
    }

    @ParameterizedTest(name = "{displayName} [시도 시작 오프셋 초={0}]")
    @ValueSource(longs = {-1, 11})
    @DisplayName("checkout 이전이나 판정 시각보다 미래인 시도 문맥은 수락하지 않는다")
    void rejectsInvalidAttemptTime(long offset) {
        TicketReservation reservation = checkoutReservation();
        assertThat(reservation.startApproval(9001L, CHECKOUT_AT.plusSeconds(offset), CHECKOUT_AT.plusSeconds(10)))
                .isFalse();
        assertThat(reservation.getCurrentPaymentId()).isNull();
        assertThat(reservation.getApprovalStartedAt()).isNull();
    }

    private TicketReservation checkoutReservation() {
        TicketReservation reservation = heldReservation();
        reservation.startCheckout(7001L, CHECKOUT_AT, PAYMENT_DEADLINE);
        return reservation;
    }

    private TicketReservation heldReservation() {
        return TicketReservation.createHeld(1L, 2L, "hold-request", new byte[32],
                CHECKOUT_AT.plusSeconds(240), CHECKOUT_AT.minusSeconds(60));
    }
}
