package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.restless.fanmeeting.application.FanMeetingOrderExpiry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

class FanMeetingExpiryCursorTest extends FanMeetingPromotionSupport {
    @MockitoSpyBean FanMeetingOrderExpiry expiry;
    @Autowired MeterRegistry meters;

    @Test
    void failedFirstOrderDoesNotStarveNextAndIsRetriedNextRun() throws Exception {
        Long event = drawnEvent(2, 0, 2);
        List<Long> orders = winnerOrderIds(event);
        Long a = orders.stream().min(Long::compareTo).orElseThrow();
        Long b = orders.stream().max(Long::compareTo).orElseThrow();
        clock.fixed = NOON.plus(Duration.ofHours(2));
        doThrow(new IllegalStateException("A fails")).when(expiry).expire(a);
        int oldBatch = (int) ReflectionTestUtils.getField(expiryWorker, "batchSize");
        ReflectionTestUtils.setField(expiryWorker, "batchSize", 1);
        try {
            expiryWorker.expireDue();
            assertThat(orderStatus(b)).isEqualTo("EXPIRED");
            assertThat(orderStatus(a)).isEqualTo("PENDING_PAYMENT");
            expiryWorker.expireDue();
            verify(expiry, times(2)).expire(a);
        } finally {
            ReflectionTestUtils.setField(expiryWorker, "batchSize", oldBatch);
        }
    }

    @Test
    void failedFirstReviewDoesNotStarveNextWarning() throws Exception {
        Long event = drawnEvent(2, 0, 2);
        List<Long> orders = winnerOrderIds(event).stream().sorted().toList();
        orders.forEach(this::review);
        clock.fixed = NOON.plus(Duration.ofMinutes(30));
        doThrow(new IllegalStateException("A fails")).when(expiry).warnStaleReview(orders.getFirst());
        int oldBatch = (int) ReflectionTestUtils.getField(expiryWorker, "batchSize");
        ReflectionTestUtils.setField(expiryWorker, "batchSize", 1);
        try {
            expiryWorker.warnStaleReviews();
            assertThat(orderColumn(orders.getLast(), "review_warned_at")).isNotNull();
            expiryWorker.warnStaleReviews();
            verify(expiry, times(2)).warnStaleReview(orders.getFirst());
        } finally {
            ReflectionTestUtils.setField(expiryWorker, "batchSize", oldBatch);
        }
    }
}
