package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.restless.fanmeeting.application.FanMeetingEntryTransitions;
import com.restless.fanmeeting.application.FanMeetingEventService;
import static org.mockito.ArgumentMatchers.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

class FanMeetingEditOpenRaceTest extends FanMeetingPromotionSupport {
    @Autowired FanMeetingEntryTransitions transitions;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean FanMeetingEventService service;

    String owner(Long id) {
        Long user=jdbc.queryForObject("SELECT organizer_id FROM fan_meeting_events WHERE event_id=?",Long.class,id);
        return "Bearer "+tokenProvider.issue(user,"ORGANIZER").value();
    }

    @ParameterizedTest
    @ValueSource(ints={-1,0,1})
    void cancelVersusOpen(int offset) throws Exception {
        Long id=createEvent(true);
        setWindow(id,"SCHEDULED",NOON,NOON.plusSeconds(600));
        clock.fixed=NOON.plusSeconds(offset);
        String owner=owner(id);
        AtomicInteger turn=new AtomicInteger();
        List<Integer> results=concurrently(2,()-> {
            if(turn.getAndIncrement()==0) {
                return mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id)
                        .header("Authorization",owner)).andReturn().getResponse().getStatus();
            }
            return transitions.open(id)==FanMeetingEntryTransitions.OpenResult.OPENED?1:0;
        });
        assertThat(results).containsExactlyInAnyOrder(offset<0?200:409,offset<0?0:1);
        assertThat(eventStatus(id)).isEqualTo(offset<0?"CANCELLED":"OPEN");
    }

    @ParameterizedTest
    @ValueSource(ints={-1,0,1})
    void editVersusOpen(int offset) throws Exception {
        Long id=createEvent(true);
        setWindow(id,"SCHEDULED",NOON,NOON.plusSeconds(600));
        clock.fixed=NOON.plusSeconds(offset);
        String owner=owner(id);
        AtomicInteger turn=new AtomicInteger();
        List<Integer> results=concurrently(2,()-> {
            if(turn.getAndIncrement()==0) {
                return mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id)
                        .header("Authorization",owner).contentType("application/json")
                        .content(FanMeetingEditApiTest.body(NOON.plusSeconds(3600),"수정 경합")))
                        .andReturn().getResponse().getStatus();
            }
            return transitions.open(id)==FanMeetingEntryTransitions.OpenResult.OPENED?1:0;
        });
        assertThat(results).containsExactlyInAnyOrder(offset<0?200:409,offset<0?0:1);
        assertThat(eventStatus(id)).isEqualTo(offset<0?"SCHEDULED":"OPEN");
        assertThat(jdbc.queryForObject("SELECT title FROM fan_meeting_events WHERE event_id=?",String.class,id))
                .isEqualTo(offset<0?"수정 경합":"연말 팬미팅");
    }

    @Test
    void editVersusCancelHasSerializedResult() throws Exception {
        Long id=createEvent(true);
        setWindow(id,"SCHEDULED",NOON.plusSeconds(60),NOON.plusSeconds(660));
        clock.fixed=NOON;
        String owner=owner(id);
        AtomicInteger turn=new AtomicInteger();
        List<Integer> results=concurrently(2,()->turn.getAndIncrement()==0
                ?mockMvc.perform(put("/api/organizer/fan-meetings/{id}",id).header("Authorization",owner)
                    .contentType("application/json").content(FanMeetingEditApiTest.body(NOON.plusSeconds(3600),"직렬 수정")))
                    .andReturn().getResponse().getStatus()
                :mockMvc.perform(post("/api/organizer/fan-meetings/{id}/cancel",id).header("Authorization",owner))
                    .andReturn().getResponse().getStatus());
        assertThat(results).allMatch(code->code==200||code==409);
        assertThat(results).contains(200);
        assertThat(eventStatus(id)).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void waitingMutationRechecksAfterOpenCommits(boolean cancel) throws Exception {
        Long id=createEvent(true);
        setWindow(id,"SCHEDULED",NOON,NOON.plusSeconds(600));
        clock.fixed=NOON.minusSeconds(1);
        String owner=owner(id);
        CountDownLatch held=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        CountDownLatch attempted=new CountDownLatch(1);
        if (cancel) {
            doAnswer(call -> { attempted.countDown(); return call.callRealMethod(); })
                    .when(service).cancel(anyLong(), eq(id));
        } else {
            doAnswer(call -> { attempted.countDown(); return call.callRealMethod(); })
                    .when(service).update(anyLong(), eq(id), any());
        }
        CompletableFuture<Void> opener=CompletableFuture.runAsync(()->tx.executeWithoutResult(s->{
            jdbc.queryForList("SELECT event_id FROM fan_meeting_events WHERE event_id=? FOR UPDATE",id);
            held.countDown();
            try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException e){throw new IllegalStateException(e);}
            clock.fixed=NOON;
            transitions.open(id);
        }));
        assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> mutation=CompletableFuture.supplyAsync(()->{
            try {
                var request=cancel?post("/api/organizer/fan-meetings/{id}/cancel",id)
                        :put("/api/organizer/fan-meetings/{id}",id).contentType("application/json")
                            .content(FanMeetingEditApiTest.body(NOON.plusSeconds(3600),"待機"));
                return mockMvc.perform(request.header("Authorization",owner)).andReturn().getResponse().getStatus();
            } catch(Exception e){throw new IllegalStateException(e);}
        });
        try {
            assertThat(attempted.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(mutation).isNotDone();
        } finally {
            release.countDown();
            opener.get(10,TimeUnit.SECONDS);
        }
        assertThat(mutation.get(10,TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(eventStatus(id)).isEqualTo("OPEN");
    }
}
