package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.restless.ticketing.infrastructure.TicketReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("티켓팅 만료 스케줄러")
class SeatHoldExpirationSchedulerTest {
    @Test
    @DisplayName("결제 만료 후보 조회·개별 처리 실패를 기록하고 다음 실행과 나머지 예약을 처리한다")
    void paymentFailureIsIsolatedAndRetriedNextRun() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(TicketReservationService.class);
        var payments = mock(TicketPaymentService.class);
        var metrics = new SimpleMeterRegistry();
        try {
            when(reservations.findExpiredPaymentIds()).thenThrow(new IllegalStateException("candidate query failed"))
                    .thenReturn(List.of(1L, 2L));
            when(payments.expirePayment(1L)).thenThrow(new IllegalStateException("reservation failed"));
            when(payments.expirePayment(2L)).thenReturn(true);
            var scheduler = new SeatHoldExpirationScheduler(reservations, holds, payments, metrics);
            scheduler.expirePayments();
            scheduler.expirePayments();
            verify(payments).expirePayment(2L);
            verifyNoInteractions(holds);
            assertThat(metrics.get("ticketing.payment.expiration.failures").counter().count()).isEqualTo(2);
        } finally {
            metrics.close();
        }
    }
}
