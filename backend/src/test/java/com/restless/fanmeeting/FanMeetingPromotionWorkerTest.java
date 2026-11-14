package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.fanmeeting.application.FanMeetingPromotion;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승격 Worker와 모집 완료 (`transactions.md` 10·11절). 스케줄러는 테스트에서 꺼져 있어 워커를 직접 부른다.
 * 매 테스트 끝에 정원 불변식(INV-FM-07: 확정 + 결제 대기 <= 정원)을 확인한다.
 */
@DisplayName("팬미팅 승격 Worker")
@ExtendWith(OutputCaptureExtension.class)
class FanMeetingPromotionWorkerTest extends FanMeetingPromotionSupport {

    @Autowired FanMeetingPromotion promotion;

    @Test
    @DisplayName("빈자리가 없으면 승격하지 않는다")
    void 빈자리가_없으면_승격하지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 2, 4);

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("WAITING", "WAITING");
        assertThat(promotedOrders(eventId)).isEmpty();
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("C28 결제 포기 → 다음 회차에 예비 1번이 승격: 결제 대기, 주문(참가비, 발급 = 지금, 기한 60분), 승격 알림 기록")
    void 포기한_자리에_예비_1번을_승격한다(CapturedOutput output) throws Exception {
        Long eventId = drawnEvent(2, 2, 4);
        Long declined = winnerOrderIds(eventId).get(0);
        Long userId = ((Number) orderColumn(declined, "user_id")).longValue();
        MvcResult res = decline(bearerOf(userId), eventId, declined);
        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING", "WAITING");
        List<Map<String, Object>> promoted = promotedOrders(eventId);
        assertThat(promoted).hasSize(1);
        Map<String, Object> order = promoted.get(0);
        assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(((Number) order.get("amount")).longValue()).isEqualTo(50000L);
        assertThat(((Number) order.get("user_id")).longValue()).isEqualTo(reserveUserId(eventId, 1));
        assertThat(((LocalDateTime) order.get("issued_at")).toInstant(ZoneOffset.UTC)).isEqualTo(NOON);
        assertThat(output.getAll()).contains("FANMEETING_NOTIFICATION type=PROMOTION eventId=" + eventId
                + " orderId=" + order.get("order_id") + " paymentDeadline=" + NOON.plus(PAYMENT_WINDOW));
        assertThat(occupied(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("빈자리 2개, 예비 3명 → 순번대로 1·2번만 승격 (INV-FM-08). 다시 돌려도 더 승격하지 않는다")
    void 빈자리만큼_순번대로_승격한다() throws Exception {
        Long eventId = drawnEvent(3, 3, 6);
        vacate(winnerOrderIds(eventId).get(0));
        vacate(winnerOrderIds(eventId).get(1));

        promotionWorker.promoteDue();
        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING", "PAYMENT_PENDING", "WAITING");
        assertThat(promotedOrders(eventId)).hasSize(2);
        assertThat(occupied(eventId)).isEqualTo(3);
    }

    @Test
    @DisplayName("C22 승격된 예비가 결제하지 않아 만료 → 다음 순번이 승격, 정원 유지")
    void 승격된_예비가_만료되면_다음_순번을_승격한다() throws Exception {
        Long eventId = drawnEvent(1, 2, 3);
        vacate(winnerOrderIds(eventId).get(0));
        promotionWorker.promoteDue();
        Long firstPromoted = ((Number) promotedOrders(eventId).get(0).get("order_id")).longValue();

        clock.fixed = NOON.plus(PAYMENT_WINDOW);
        expiryWorker.expireDue();
        promotionWorker.promoteDue();

        assertThat(orderStatus(firstPromoted)).isEqualTo("EXPIRED");
        assertThat(reserveStatuses(eventId)).containsExactly("EXPIRED", "PAYMENT_PENDING");
        assertThat(promotedOrders(eventId)).hasSize(2);
        assertThat(occupied(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("C11 보류 중인 자리는 넘기지 않는다. 다른 빈자리는 승격하고, 보류가 있으면 모집 완료하지 않는다 (INV-FM-11)")
    void 보류_자리는_승격하지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 1, 3);
        review(winnerOrderIds(eventId).get(0));
        vacate(winnerOrderIds(eventId).get(1));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING");
        assertThat(promotedOrders(eventId)).hasSize(1);
        assertThat(orderStatus(winnerOrderIds(eventId).get(0))).isEqualTo("PAYMENT_REVIEW");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(occupied(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("C15 승격 Worker 2개가 동시에 돌아도 승격 합계 = 빈자리")
    void 승격_Worker가_동시에_돌아도_빈자리만큼만_승격한다() throws Exception {
        Long eventId = drawnEvent(3, 5, 8);
        vacate(winnerOrderIds(eventId).get(0));
        vacate(winnerOrderIds(eventId).get(1));

        concurrently(2, () -> {
            promotionWorker.promoteDue();
            return true;
        });

        assertThat(promotedOrders(eventId)).hasSize(2);
        assertThat(reserveStatuses(eventId))
                .containsExactly("PAYMENT_PENDING", "PAYMENT_PENDING", "WAITING", "WAITING", "WAITING");
        assertThat(occupied(eventId)).isEqualTo(3);
    }

    @Test
    @DisplayName("빈자리는 행사를 잠근 뒤에 센다: 승격이 잠금을 기다리는 사이 비워진 자리도 같은 회차에 채운다")
    void 빈자리는_행사를_잠근_뒤에_센다() throws Exception {
        Long eventId = drawnEvent(3, 4, 7);
        List<Long> winners = winnerOrderIds(eventId);
        vacate(winners.get(0));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 만료 처리처럼 행사를 공유 잠금으로 쥔 트랜잭션이 자리를 하나 더 비우고 아직 커밋하지 않았다.
        CompletableFuture<Void> expiring = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.queryForObject("SELECT event_id FROM fan_meeting_events WHERE event_id = ? FOR SHARE",
                            Long.class, eventId);
                    vacate(winners.get(1));
                    held.countDown();
                    await(release);
                }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> promoting = CompletableFuture.runAsync(promotionWorker::promoteDue);
        Thread.sleep(500);
        assertThat(promoting).as("승격은 행사 잠금을 기다린다").isNotDone();
        assertThat(promotedOrders(eventId)).isEmpty();

        release.countDown();
        expiring.get(10, TimeUnit.SECONDS);
        promoting.get(10, TimeUnit.SECONDS);

        assertThat(promotedOrders(eventId)).as("잠근 뒤에 세면 빈자리는 2개").hasSize(2);
        assertThat(occupied(eventId)).isEqualTo(3);
    }

    @Test
    @DisplayName("C23 승격과 결제 승인이 동시에: 승격은 승인이 끝나길 기다리고, 정원을 넘지 않는다 (INV-FM-07)")
    void 승격과_결제_승인이_겹쳐도_정원을_넘지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 2, 4);
        List<Long> winners = winnerOrderIds(eventId);
        vacate(winners.get(0));
        Long paying = winners.get(1);
        Long attempt = startApproval(paying);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> approving = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    handler().onApproved(paying, approved(attempt, NOON.plusSeconds(10)));
                    held.countDown();
                    await(release);
                }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> promoting = CompletableFuture.runAsync(promotionWorker::promoteDue);
        Thread.sleep(500);
        assertThat(promoting).as("승격은 승인 트랜잭션이 끝나길 기다린다").isNotDone();

        release.countDown();
        approving.get(10, TimeUnit.SECONDS);
        promoting.get(10, TimeUnit.SECONDS);

        assertThat(orderStatus(paying)).isEqualTo("PAID");
        assertThat(promotedOrders(eventId)).hasSize(1);
        assertThat(occupied(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("C31 승격 마감 경계: 새 주문 기한이 마감과 같으면 승격, 1초 넘으면 승격하지 않는다 (INV-FM-16)")
    void 새_주문의_기한이_승격_마감을_넘으면_승격하지_않는다() throws Exception {
        Long tooLate = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(tooLate).get(0));
        setPromotionClosesAt(tooLate, NOON.plus(PAYMENT_WINDOW).minusSeconds(1));
        Long justInTime = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(justInTime).get(0));
        setPromotionClosesAt(justInTime, NOON.plus(PAYMENT_WINDOW));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(tooLate)).containsExactly("WAITING");
        assertThat(promotedOrders(tooLate)).isEmpty();
        assertThat(eventStatus(tooLate)).as("승격 마감 전이고 예비가 남아 완료하지 않는다").isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(reserveStatuses(justInTime)).containsExactly("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("C31 승격 마감이 지나고 결제 대기 0 → 모집 완료, 남은 예비는 NOT_NEEDED. 마감 1초 전에는 완료하지 않는다 (INV-FM-15)")
    void 승격_마감이_지나면_모집을_완료한다() throws Exception {
        Long eventId = drawnEvent(2, 2, 4);
        pay(winnerOrderIds(eventId).get(0));
        vacate(winnerOrderIds(eventId).get(1));
        setPromotionClosesAt(eventId, NOON.plusSeconds(1));

        promotionWorker.promoteDue();
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(reserveStatuses(eventId)).containsExactly("WAITING", "WAITING");

        clock.fixed = NOON.plusSeconds(1);
        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "NOT_NEEDED");
        assertThat(promotedOrders(eventId)).isEmpty();
    }

    @Test
    @DisplayName("C31 승격 마감이 지나도 보류가 남아 있으면 완료하지 않고 예비도 그대로 둔다")
    void 승격_마감_뒤에도_보류가_있으면_완료하지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 1, 3);
        pay(winnerOrderIds(eventId).get(0));
        review(winnerOrderIds(eventId).get(1));
        setPromotionClosesAt(eventId, NOON.minusSeconds(1));

        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(reserveStatuses(eventId)).containsExactly("WAITING");
    }

    @Test
    @DisplayName("C32 야간(23:30)에는 빈자리가 있어도 승격하지 않고, 09:00이 되면 승격한다. 08:59:59까지는 안 한다")
    void 야간에는_승격하지_않는다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(eventId).get(0));
        setPromotionClosesAt(eventId, NOON.plus(Duration.ofDays(2)));

        clock.fixed = seoul(23, 30, 0);
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("WAITING");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");

        clock.fixed = seoul(8, 59, 59).plus(Duration.ofDays(1));
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("WAITING");

        Instant nine = seoul(9, 0, 0).plus(Duration.ofDays(1));
        clock.fixed = nine;
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING");
        assertThat(((LocalDateTime) promotedOrders(eventId).get(0).get("issued_at")).toInstant(ZoneOffset.UTC))
                .isEqualTo(nine);
    }

    @Test
    @DisplayName("야간에도 모집 완료 판정은 한다")
    void 야간에도_모집을_완료한다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        pay(winnerOrderIds(eventId).get(0));

        clock.fixed = seoul(23, 30, 0);
        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED");
    }

    @Test
    @DisplayName("C33 예비 1번이 탈퇴했으면 승격 차례에 NOT_NEEDED로 바꾸고 2번을 승격한다")
    void 탈퇴한_예비는_건너뛴다() throws Exception {
        Long eventId = drawnEvent(1, 2, 3);
        vacate(winnerOrderIds(eventId).get(0));
        withdraw(reserveUserId(eventId, 1));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "PAYMENT_PENDING");
        List<Map<String, Object>> promoted = promotedOrders(eventId);
        assertThat(promoted).hasSize(1);
        assertThat(((Number) promoted.get(0).get("user_id")).longValue()).isEqualTo(reserveUserId(eventId, 2));
        assertThat(occupied(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("미리 확인한 범위(빈자리 × 2명)가 모두 탈퇴자면 이번 회차는 거기서 멈추고, 다음 회차에 이어서 승격한다")
    void 미리_확인한_범위를_넘으면_다음_회차에_이어_간다() throws Exception {
        Long eventId = drawnEvent(1, 3, 4);
        vacate(winnerOrderIds(eventId).get(0));
        withdraw(reserveUserId(eventId, 1));
        withdraw(reserveUserId(eventId, 2));

        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "NOT_NEEDED", "WAITING");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");

        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "NOT_NEEDED", "PAYMENT_PENDING");
    }

    @Test
    @DisplayName("미리 확인한 뒤 예비 줄이 바뀌었으면, 확인하지 않은 예비는 건드리지 않는다 (탈퇴자로 잘못 처리하지 않는다)")
    void 미리_확인하지_않은_예비는_건드리지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 3, 5);
        vacate(winnerOrderIds(eventId).get(0));
        vacate(winnerOrderIds(eventId).get(1));
        // 예비 1·2번만 미리 확인했다(둘 다 탈퇴하지 않음).
        FanMeetingPromotion.Preview preview = new FanMeetingPromotion.Preview(
                Set.of(reserveSelectionId(eventId, 1), reserveSelectionId(eventId, 2)),
                Set.of(reserveUserId(eventId, 1), reserveUserId(eventId, 2)));
        // 그 사이 다른 처리가 1번을 줄에서 뺐다. 이제 기다리는 예비의 앞 두 명은 2·3번이다.
        jdbc.update("UPDATE fan_meeting_selections SET status = 'NOT_NEEDED' WHERE selection_id = ?",
                reserveSelectionId(eventId, 1));

        promotion.promote(eventId, preview);

        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "PAYMENT_PENDING", "WAITING");
        assertThat(promotedOrders(eventId)).hasSize(1);
    }

    @Test
    @DisplayName("하나 남은 예비가 탈퇴자 → NOT_NEEDED로 바꾸고, 채울 사람이 없으니 같은 회차에 모집 완료")
    void 마지막_예비가_탈퇴자면_같은_회차에_완료한다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(eventId).get(0));
        withdraw(reserveUserId(eventId, 1));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED");
        assertThat(promotedOrders(eventId)).isEmpty();
        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
    }

    @Test
    @DisplayName("모집 완료 ①: 당첨자 전원 결제 → 완료, 남은 예비 NOT_NEEDED (INV-FM-15)")
    void 정원이_다_차면_모집을_완료한다() throws Exception {
        Long eventId = drawnEvent(2, 2, 4);
        winnerOrderIds(eventId).forEach(this::pay);

        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(reserveStatuses(eventId)).containsExactly("NOT_NEEDED", "NOT_NEEDED");
        assertThat(occupied(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("모집 완료 ②: 자리가 남아도 예비와 결제 대기가 모두 0이면 완료. 환불 대기(REFUND_PENDING)는 막지 않는다")
    void 채울_사람이_없으면_모집을_완료한다() throws Exception {
        Long eventId = drawnEvent(2, 0, 2);
        pay(winnerOrderIds(eventId).get(0));
        setOrder(winnerOrderIds(eventId).get(1), "REFUND_PENDING", "EXPIRED");

        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
    }

    @Test
    @DisplayName("결제 대기가 남아 있으면 완료하지 않는다")
    void 결제_대기가_있으면_완료하지_않는다() throws Exception {
        Long eventId = drawnEvent(2, 0, 2);
        pay(winnerOrderIds(eventId).get(0));

        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("마지막 예비까지 승격한 회차에는 완료하지 않는다 (결제 대기가 생겼으므로)")
    void 승격으로_결제_대기가_생기면_완료하지_않는다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(eventId).get(0));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("C36 승격 저장 중 오류 → 전체 롤백, 알림 기록 0건, 실패 메트릭. 다른 행사는 계속 처리한다")
    void 승격이_롤백되면_알림을_기록하지_않는다(CapturedOutput output) throws Exception {
        Long broken = drawnEvent(1, 2, 3);
        vacate(winnerOrderIds(broken).get(0));
        // 예비 1번에게 이미 주문이 있으면, 승격이 주문을 만들 때 UNIQUE(selection_id) 위반이 난다.
        jdbc.update("""
                INSERT INTO fan_meeting_orders (event_id, selection_id, user_id, amount, status, issued_at,
                       created_at, updated_at)
                SELECT event_id, selection_id, user_id, 50000, 'EXPIRED', ?, ?, ?
                  FROM fan_meeting_selections
                 WHERE event_id = ? AND selection_type = 'RESERVE' AND selection_rank = 1
                """, utc(NOON), utc(NOON), utc(NOON), broken);
        Long healthy = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(healthy).get(0));

        promotionWorker.promoteDue();

        assertThat(reserveStatuses(broken)).containsExactly("WAITING", "WAITING");
        assertThat(output.getAll()).doesNotContain("FANMEETING_NOTIFICATION type=PROMOTION eventId=" + broken + " ");
        assertThat(reserveStatuses(healthy)).containsExactly("PAYMENT_PENDING");
        assertThat(output.getAll()).contains("FANMEETING_NOTIFICATION type=PROMOTION eventId=" + healthy + " ");
    }

    @Test
    @DisplayName("행사를 찾은 뒤 잠그기 전에 모집이 끝났으면, 잠근 뒤 상태를 다시 보고 아무것도 하지 않는다")
    void 잠근_뒤_행사_상태를_다시_확인한다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(eventId).get(0));
        FanMeetingPromotion.Preview preview = promotion.preview(eventId);
        jdbc.update("UPDATE fan_meeting_events SET status = 'RECRUITMENT_COMPLETED' WHERE event_id = ?", eventId);

        assertThat(promotion.promote(eventId, preview)).isEmpty();

        assertThat(reserveStatuses(eventId)).containsExactly("WAITING");
        assertThat(promotedOrders(eventId)).isEmpty();
    }

    @Test
    @DisplayName("방어: 정원이 다 찬 것처럼 보여도 보류 주문이 있으면 완료하지 않는다 (정상 흐름에서는 생기지 않는 상태)")
    void 보류_주문이_있으면_정원이_차_보여도_완료하지_않는다() throws Exception {
        Long eventId = drawnEvent(1, 1, 2);
        pay(winnerOrderIds(eventId).get(0));
        // 예비 1번에게 보류 주문을 직접 붙인다. 확정 1명 = 정원인데 보류가 1건 있는, 불변식이 깨진 상태다.
        jdbc.update("UPDATE fan_meeting_selections SET status = 'PAYMENT_PENDING' WHERE selection_id = ?",
                reserveSelectionId(eventId, 1));
        jdbc.update("""
                INSERT INTO fan_meeting_orders (event_id, selection_id, user_id, amount, status, issued_at,
                       review_started_at, created_at, updated_at)
                SELECT event_id, selection_id, user_id, 50000, 'PAYMENT_REVIEW', ?, ?, ?, ?
                  FROM fan_meeting_selections WHERE selection_id = ?
                """, utc(NOON), utc(NOON), utc(NOON), utc(NOON), reserveSelectionId(eventId, 1));

        promotionWorker.promoteDue();

        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
    }

    @Test
    @DisplayName("결제 진행 중이 아닌 행사(추첨 전, 모집 완료)는 건드리지 않는다")
    void 결제_진행_중이_아닌_행사는_건드리지_않는다() throws Exception {
        Long completed = drawnEvent(1, 1, 2);
        vacate(winnerOrderIds(completed).get(0));
        jdbc.update("UPDATE fan_meeting_events SET status = 'RECRUITMENT_COMPLETED' WHERE event_id = ?", completed);
        clock.fixed = null;
        Long closed = closedEventWithApplicants(1, 1, 2);
        clock.fixed = NOON;

        promotionWorker.promoteDue();

        assertThat(eventStatus(closed)).isEqualTo("CLOSED");
        assertThat(reserveStatuses(completed)).containsExactly("WAITING");
        assertThat(eventStatus(completed)).isEqualTo("RECRUITMENT_COMPLETED");
    }
}
