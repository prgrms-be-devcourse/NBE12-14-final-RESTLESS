package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.fanmeeting.application.FanMeetingEntryTransitions;
import com.restless.fanmeeting.application.FanMeetingEntryTransitions.OpenResult;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 열기·마감은 판정에 쓰는 시각을 행사를 잠근 뒤에 읽는다 (`transactions.md` 1절 11번, 2절).
 * 잠금을 기다리는 사이 시각이 지났으면 지난 시각으로 판정해야 한다.
 */
@DisplayName("팬미팅 열기·마감: 시각은 잠근 뒤에 읽는다")
class FanMeetingEntryLockTimeTest extends FanMeetingApiSupport {

    @Autowired FanMeetingEntryTransitions transitions;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("잠금을 기다리는 사이 오픈 시각이 지나면 연다")
    void 잠금을_기다리는_사이_오픈_시각이_지나면_연다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "SCHEDULED", now.plusSeconds(2), now.plus(Duration.ofMinutes(10)));

        OpenResult result = whileEventLockedFor(eventId, Duration.ofMillis(2500), () -> transitions.open(eventId));

        assertThat(result).isEqualTo(OpenResult.OPENED);
        assertThat(statusOf(eventId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("잠금을 기다리는 사이 마감 시각이 지나면 닫고 추첨 실행을 만든다")
    void 잠금을_기다리는_사이_마감_시각이_지나면_닫는다() throws Exception {
        Long eventId = createEvent(false);
        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(5)), now.plusSeconds(2));

        Boolean closed = whileEventLockedFor(eventId, Duration.ofMillis(2500), () -> transitions.close(eventId));

        assertThat(closed).isTrue();
        assertThat(statusOf(eventId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_draw_runs WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    /**
     * 다른 트랜잭션이 행사를 잠근 동안 task를 시작한다. task는 행사 잠금을 기다리다가 held 뒤에 잠금을 얻는다.
     */
    <T> T whileEventLockedFor(Long eventId, Duration held, Supplier<T> task) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT event_id FROM fan_meeting_events WHERE event_id = ? FOR UPDATE",
                            Long.class, eventId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<T> running = CompletableFuture.supplyAsync(task);
        Thread.sleep(held.toMillis());
        assertThat(running).as("행사 잠금을 기다린다").isNotDone();
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        return running.get(30, TimeUnit.SECONDS);
    }

    String statusOf(Long eventId) {
        return jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?", String.class, eventId);
    }
}
