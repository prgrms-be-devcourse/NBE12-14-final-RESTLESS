package com.restless.drop.application;

import com.restless.drop.infrastructure.DropReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DropReservationExpiryJobTest {
    @Test
    void itemFailureIsCountedAndRemainingItemsStillProcessed() {
        var reservations = mock(DropReservationRepository.class);
        var lifecycle = mock(DropReservationLifecycleService.class);
        var meters = new SimpleMeterRegistry();
        when(reservations.findExpiryCandidates(any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(lifecycle.expire(1L)).thenThrow(new IllegalStateException("DB unavailable"));
        when(lifecycle.expire(2L)).thenReturn(true);
        when(lifecycle.expire(3L)).thenReturn(false);
        new DropReservationExpiryJob(reservations, lifecycle, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), meters).runOnce();
        assertThat(meters.get("drop.expiry.processed").tag("result", "failed").counter().count()).isEqualTo(1);
        assertThat(meters.get("drop.expiry.processed").tag("result", "expired").counter().count()).isEqualTo(1);
        assertThat(meters.get("drop.expiry.processed").tag("result", "skipped").counter().count()).isEqualTo(1);
    }
}
