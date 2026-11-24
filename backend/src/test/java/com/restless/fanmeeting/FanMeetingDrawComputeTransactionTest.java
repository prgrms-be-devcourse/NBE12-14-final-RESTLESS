package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.restless.fanmeeting.application.FanMeetingDrawTransitions;
import com.restless.fanmeeting.application.MemberPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 같은 members 스파이를 쓰는 FanMeetingPreviewCompletionTest와 Spring 컨텍스트를 같이 쓰도록 같은 ClockConfig를 가져온다. */
@Import(FanMeetingPromotionSupport.ClockConfig.class)
class FanMeetingDrawComputeTransactionTest extends FanMeetingOrderApiSupport {
    @Autowired FanMeetingDrawTransitions draws;
    @MockitoSpyBean MemberPort members;

    @Test
    void memberLookupAndRankingRunOutsideTransactionWithStableResults() throws Exception {
        Long event = closedEventWithApplicants(2, 1, 4);
        Long run = jdbc.queryForObject("SELECT draw_run_id FROM fan_meeting_draw_runs WHERE event_id = ?",
                Long.class, event);
        var claim = draws.claim(run).orElseThrow();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(members).activeMemberIds(any());

        var first = draws.compute(claim);
        var second = draws.compute(claim);

        assertThat(first.winners().stream().map(c -> c.getApplicationId()).toList())
                .hasSize(2).isEqualTo(second.winners().stream().map(c -> c.getApplicationId()).toList());
        assertThat(first.reserves().stream().map(c -> c.getApplicationId()).toList())
                .hasSize(1).isEqualTo(second.reserves().stream().map(c -> c.getApplicationId()).toList());
        assertThat(draws.finalizeDraw(claim, first)).hasSize(2);
    }
}
