package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.fanmeeting.application.FanMeetingEntryWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 응모 동시 요청과 실패 처리 (transactions.md 3절, test-scenarios C01·C02·C45·C52). */
class FanMeetingApplicationConcurrencyTest extends FanMeetingApiSupport {

    @Autowired TransactionTemplate tx;
    @Autowired FanMeetingEntryWorker worker;

    @Test
    void 재시도가_모두_잠금_대기로_실패하면_503_APPLICATION_BUSY이고_응모는_0건_C52() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT event_id FROM fan_meeting_events WHERE event_id = ? FOR UPDATE", eventId);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        String fan = token("USER");
        long startedAt = System.nanoTime();
        try {
            apply(fan, eventId)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("APPLICATION_BUSY"));
            // 시도마다 트랜잭션 timeout(5초)까지 기다리므로, 3회를 다 했으면 최소 15초 가까이 걸린다.
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThan(Duration.ofSeconds(14));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(countApplications(eventId)).isZero();
    }

    @Test
    void 같은_회원_첫_응모_2건_동시면_1건만_응모되고_나머지는_ALREADY_APPLIED_C01() throws Exception {
        sameMemberFirstApplies(2);
    }

    @Test
    void 같은_회원_첫_응모_10건_동시여도_1건만_응모되고_5xx는_0_C45() throws Exception {
        sameMemberFirstApplies(10);
    }

    @Test
    void 취소한_뒤_재응모_2건_동시면_하나만_APPLIED가_되고_나머지는_ALREADY_APPLIED_C02() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");
        apply(fan, eventId).andExpect(status().isCreated());
        cancel(fan, eventId).andExpect(status().isNoContent());

        List<MvcResult> results = concurrently(2, () -> apply(fan, eventId).andReturn());

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 409)
                .allSatisfy(r -> assertThat(contentOf(r)).contains("\"ALREADY_APPLIED\""));
        assertThat(countApplications(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM fan_meeting_applications WHERE event_id = ?",
                String.class, eventId)).isEqualTo("APPLIED");
    }

    /**
     * 시각 확인을 통과한 응모가 잠금을 쥔 채 마감 시각을 넘겨 커밋하는 경우. 운영 코드에 장치를 넣지 않고,
     * 테스트 트랜잭션이 응모 트랜잭션 역할(Event FOR SHARE → 응모 INSERT)을 한다.
     */
    @Test
    void 응모와_마감이_겹치면_마감은_응모_커밋을_기다리고_그_응모는_추첨_후보로_남는다_C03() throws Exception {
        Long eventId = createEvent(true);
        Instant closesAt = Instant.now().plusSeconds(2);
        setWindow(eventId, "OPEN", closesAt.minus(Duration.ofMinutes(10)), closesAt);
        Long userId = userIdOf(token("USER"));
        CountDownLatch shareHeld = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        CompletableFuture<Void> inFlightApply = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("SELECT event_id FROM fan_meeting_events WHERE event_id = ? FOR SHARE", eventId);
            shareHeld.countDown();
            await(commit);
            jdbc.update("""
                    INSERT INTO fan_meeting_applications (event_id, user_id, status, applied_at, updated_at)
                    VALUES (?, ?, 'APPLIED', ?, ?)
                    """, eventId, userId, utc(Instant.now()), utc(Instant.now()));
        }));
        assertThat(shareHeld.await(10, TimeUnit.SECONDS)).isTrue();
        while (!Instant.now().isAfter(closesAt.plusMillis(200))) {
            Thread.sleep(50);
        }

        CompletableFuture<Void> close = CompletableFuture.runAsync(worker::closeDue);
        Thread.sleep(500);
        assertThat(close).as("마감은 응모의 공유 잠금이 풀릴 때까지 기다린다").isNotDone();

        commit.countDown();
        inFlightApply.get(10, TimeUnit.SECONDS);
        close.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?",
                String.class, eventId)).isEqualTo("CLOSED");
        assertThat(countApplications(eventId)).isEqualTo(1);
        apply(token("USER"), eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_WINDOW_CLOSED"));
    }

    void sameMemberFirstApplies(int requests) throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String fan = token("USER");

        List<MvcResult> results = concurrently(requests, () -> apply(fan, eventId).andReturn());

        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long already = results.stream().filter(r -> r.getResponse().getStatus() == 409
                && contentOf(r).contains("\"ALREADY_APPLIED\"")).count();
        assertThat(created).isEqualTo(1);
        assertThat(already).isEqualTo(requests - 1);
        assertThat(countApplications(eventId)).isEqualTo(1);
    }
}
