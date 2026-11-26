package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.restless.ticketing.infrastructure.TicketReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.stream.LongStream;
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

    @Test
    @DisplayName("계속 실패하는 선점 만료 후보가 한 쪽을 채워도 다음 쪽 후보를 같은 실행에서 처리한다")
    void failingHoldCandidatesDoNotHideLaterCandidates() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(TicketReservationService.class);
        var payments = mock(TicketPaymentService.class);
        var metrics = new SimpleMeterRegistry();
        try {
            List<Long> failing = fullPage(1);
            when(reservations.findExpiredHoldIds()).thenReturn(failing);
            when(reservations.findExpiredHoldIdsAfter(failing.getLast())).thenReturn(List.of(101L));
            doThrow(new IllegalStateException("mismatch")).when(holds).expireHold(longThat(id -> id <= 100));
            new SeatHoldExpirationScheduler(reservations, holds, payments, metrics).expireHolds();
            verify(holds).expireHold(101L);
            verify(reservations, never()).findExpiredHoldIdsAfter(101L);
            verifyNoInteractions(payments);
            assertThat(metrics.get("ticketing.hold.expiration.failures").counter().count()).isEqualTo(100);
        } finally {
            metrics.close();
        }
    }

    @Test
    @DisplayName("계속 실패하는 결제 만료 후보가 한 쪽을 채워도 마지막 ID 다음 쪽을 이어 읽어 처리한다")
    void failingPaymentCandidatesDoNotHideLaterCandidates() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(TicketReservationService.class);
        var payments = mock(TicketPaymentService.class);
        var metrics = new SimpleMeterRegistry();
        try {
            List<Long> failing = fullPage(1);
            List<Long> second = fullPage(101);
            when(reservations.findExpiredPaymentIds()).thenReturn(failing);
            when(reservations.findExpiredPaymentIdsAfter(100L)).thenReturn(second);
            when(reservations.findExpiredPaymentIdsAfter(200L)).thenReturn(List.of());
            doThrow(new IllegalStateException("mismatch")).when(payments).expirePayment(longThat(id -> id <= 100));
            doReturn(true).when(payments).expirePayment(longThat(id -> id > 100));
            new SeatHoldExpirationScheduler(reservations, holds, payments, metrics).expirePayments();
            second.forEach(id -> verify(payments).expirePayment(id));
            verify(reservations).findExpiredPaymentIdsAfter(200L);
            verifyNoInteractions(holds);
            assertThat(metrics.get("ticketing.payment.expiration.failures").counter().count()).isEqualTo(100);
        } finally {
            metrics.close();
        }
    }

    @Test
    @DisplayName("선점 만료 다음 쪽 조회 실패를 기록하고 이번 실행을 끝낸다")
    void holdNextPageFailureIsRecorded() {
        var reservations = mock(TicketReservationRepository.class);
        var holds = mock(TicketReservationService.class);
        var payments = mock(TicketPaymentService.class);
        var metrics = new SimpleMeterRegistry();
        try {
            when(reservations.findExpiredHoldIds()).thenReturn(fullPage(1));
            when(reservations.findExpiredHoldIdsAfter(100L)).thenThrow(new IllegalStateException("query failed"));
            new SeatHoldExpirationScheduler(reservations, holds, payments, metrics).expireHolds();
            verify(holds, times(100)).expireHold(anyLong());
            assertThat(metrics.get("ticketing.hold.expiration.failures").counter().count()).isEqualTo(1);
        } finally {
            metrics.close();
        }
    }

    private static List<Long> fullPage(long firstId) {
        return LongStream.range(firstId, firstId + TicketReservationRepository.EXPIRATION_PAGE_SIZE).boxed().toList();
    }
}
