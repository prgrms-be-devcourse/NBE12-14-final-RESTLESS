package com.restless.drop.application;

import com.restless.drop.infrastructure.DropReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** The job bean remains available when automatic scheduling is disabled. */
@Component("dropReservationExpiryJob")
@RequiredArgsConstructor
public class DropReservationExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(DropReservationExpiryJob.class);
    private final DropReservationRepository reservations;
    private final DropReservationLifecycleService lifecycle;
    private final Clock clock;
    private final MeterRegistry meters;

    public void runOnce() {
        java.util.List<Long> candidates;
        try {
            candidates = reservations.findExpiryCandidates(clock.instant(), PageRequest.of(0, 100));
        } catch (RuntimeException failure) {
            failure(null, failure);
            return;
        }
        for (var id : candidates) {
            try {
                var expired = lifecycle.expire(id);
                meters.counter("drop.expiry.processed", "result", expired ? "expired" : "skipped").increment();
            } catch (RuntimeException failure) {
                failure(id, failure);
            }
        }
    }

    private void failure(Long reservationId, RuntimeException failure) {
        log.error("Drop expiry failed reservationId={} errorType={}", reservationId, failure.getClass().getName());
        meters.counter("drop.expiry.processed", "result", "failed").increment();
    }
}
