package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class FanMeetingCompletionRegressionTest extends FanMeetingPromotionSupport {
    @MockitoSpyBean FanMeetingSelectionRepository selections;

    @Test
    void noWaitingReserveCompletesWithoutLockingEmptyRange() throws Exception {
        Long event = drawnEvent(1, 0, 1);
        pay(winnerOrderIds(event).getFirst());
        doThrow(new IllegalStateException("empty range must not be locked"))
                .when(selections).findWaitingReservesForUpdate(event, Pageable.unpaged());

        promotionWorker.promoteDue();

        assertThat(eventStatus(event)).isEqualTo("RECRUITMENT_COMPLETED");
    }
}
