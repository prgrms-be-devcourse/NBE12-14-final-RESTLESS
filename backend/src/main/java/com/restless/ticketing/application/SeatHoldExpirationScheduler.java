package com.restless.ticketing.application;

import com.restless.ticketing.infrastructure.TicketReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class SeatHoldExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpirationScheduler.class);

    private final TicketReservationRepository reservations;
    private final TicketReservationService reservationService;
    private final TicketPaymentService paymentService;
    private final MeterRegistry metrics;

    @Scheduled(fixedDelayString = "${restless.ticketing.hold-expiration-delay-ms:5000}")
    public void expireHolds() {
        for (Long reservationId : reservations.findExpiredHoldIds()) {
            try {
                reservationService.expireHold(reservationId);
            } catch (RuntimeException failure) {
                log.error("선점 예약 만료 처리 실패: reservationId={}", reservationId, failure);
                metrics.counter("ticketing.hold.expiration.failures").increment();
            }
        }
    }

    @Scheduled(fixedDelayString = "${restless.ticketing.payment-expiration-delay-ms:5000}")
    public void expirePayments() {
        java.util.List<Long> candidates;
        try {
            candidates = reservations.findExpiredPaymentIds();
        } catch (RuntimeException failure) {
            paymentFailure(null, failure);
            return;
        }
        for (Long reservationId : candidates) {
            try {
                paymentService.expirePayment(reservationId);
            } catch (RuntimeException failure) {
                paymentFailure(reservationId, failure);
            }
        }
    }

    private void paymentFailure(Long reservationId, RuntimeException failure) {
        log.error("결제 기한 만료 처리 실패: reservationId={}", reservationId, failure);
        metrics.counter("ticketing.payment.expiration.failures").increment();
    }
}
