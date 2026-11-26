package com.restless.ticketing.application;

import com.restless.ticketing.infrastructure.TicketReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
        drain(reservations::findExpiredHoldIds, reservations::findExpiredHoldIdsAfter,
                reservationService::expireHold, this::holdFailure);
    }

    @Scheduled(fixedDelayString = "${restless.ticketing.payment-expiration-delay-ms:5000}")
    public void expirePayments() {
        drain(reservations::findExpiredPaymentIds, reservations::findExpiredPaymentIdsAfter,
                paymentService::expirePayment, this::paymentFailure);
    }

    /**
     * 가득 찬 쪽 뒤에는 마지막 ID 다음부터 이어 읽는다. 계속 실패해 그대로 남은 앞쪽 후보가
     * 한 쪽을 채워도 뒤쪽 후보가 매 실행에서 처리된다. 커서는 앞으로만 가므로 실행은 끝난다.
     */
    private void drain(Supplier<List<Long>> firstPage, Function<Long, List<Long>> nextPage,
                       Consumer<Long> expire, FailureRecorder failure) {
        List<Long> page;
        try {
            page = firstPage.get();
        } catch (RuntimeException error) {
            failure.record(null, error);
            return;
        }
        while (true) {
            for (Long reservationId : page) {
                try {
                    expire.accept(reservationId);
                } catch (RuntimeException error) {
                    failure.record(reservationId, error);
                }
            }
            if (page.size() < TicketReservationRepository.EXPIRATION_PAGE_SIZE) {
                return;
            }
            Long last = page.getLast();
            try {
                page = nextPage.apply(last);
            } catch (RuntimeException error) {
                failure.record(null, error);
                return;
            }
        }
    }

    private void holdFailure(Long reservationId, RuntimeException failure) {
        log.error("선점 예약 만료 처리 실패: reservationId={}", reservationId, failure);
        metrics.counter("ticketing.hold.expiration.failures").increment();
    }

    private void paymentFailure(Long reservationId, RuntimeException failure) {
        log.error("결제 기한 만료 처리 실패: reservationId={}", reservationId, failure);
        metrics.counter("ticketing.payment.expiration.failures").increment();
    }

    @FunctionalInterface
    private interface FailureRecorder {
        void record(Long reservationId, RuntimeException failure);
    }
}
