package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.restless.fanmeeting.application.MemberPort;
import com.restless.fanmeeting.application.FanMeetingPromotion;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class FanMeetingPreviewCompletionTest extends FanMeetingPromotionSupport {
    @MockitoSpyBean MemberPort members;
    @Autowired FanMeetingPromotion promotion;

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void previewDeadlineBoundary(int offset) throws Exception {
        Long event = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(event).getFirst());
        setPromotionClosesAt(event, NOON.plus(Duration.ofHours(1)));
        clock.fixed = NOON.plusSeconds(offset);
        assertThat(promotion.preview(event).checkedSelectionIds()).hasSize(offset > 0 ? 0 : 1);
    }

    @ParameterizedTest
    @CsvSource({"22,59,59,true", "23,0,0,false", "8,59,59,false", "9,0,0,true"})
    void previewNightBoundary(int hour, int minute, int second, boolean allowed) throws Exception {
        Long event = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(event).getFirst());
        clock.fixed = seoul(hour, minute, second);
        assertThat(promotion.preview(event).checkedSelectionIds()).hasSize(allowed ? 1 : 0);
    }


    @Test
    void memberFailureDoesNotBlockCompletionAfterPromotionClose() throws Exception {
        Long event = drawnEvent(1, 2, 3);
        vacate(winnerOrderIds(event).getFirst());
        setPromotionClosesAt(event, clock.instant().minusSeconds(1));
        org.mockito.Mockito.clearInvocations(members);
        doThrow(new IllegalStateException("member lookup unavailable")).when(members).activeMemberIds(any());

        promotionWorker.promoteDue();

        assertThat(eventStatus(event)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(reserveStatuses(event)).containsExactly("NOT_NEEDED", "NOT_NEEDED");
        verify(members, never()).activeMemberIds(any());
    }
}
