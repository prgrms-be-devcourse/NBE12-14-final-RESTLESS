package com.restless.fanmeeting;

import com.restless.TestcontainersConfiguration;
import com.restless.fanmeeting.application.FanMeetingExpiryWorker;
import com.restless.fanmeeting.application.FanMeetingPromotionWorker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 승격 테스트 공통. 승격은 야간(23:00~09:00 Asia/Seoul)에 하지 않으므로, 테스트가 도는 시각에 결과가 흔들리지 않게
 * 추첨이 끝난 뒤 시계를 오늘 낮 12:00(Asia/Seoul)에 세운다. 시계를 세우기 전(응모, 마감, 추첨)은 실제 시각으로 돈다.
 */
@Import({TestcontainersConfiguration.class, FanMeetingPromotionSupport.ClockConfig.class})
abstract class FanMeetingPromotionSupport extends FanMeetingOrderApiSupport {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 오늘 낮 12:00 (Asia/Seoul). 승격할 수 있는 시각이다. */
    static final Instant NOON = LocalDate.now(SEOUL).atTime(12, 0).atZone(SEOUL).toInstant();

    @Autowired TestClock clock;
    @Autowired FanMeetingPromotionWorker promotionWorker;
    @Autowired FanMeetingExpiryWorker expiryWorker;

    @AfterEach
    void useRealClock() {
        clock.fixed = null;
    }

    /**
     * 추첨까지 끝낸 행사. 시계를 낮 12:00에 세우고, 당첨 주문의 발급 시각도 12:00으로 옮긴다(기한 13:00).
     * 승격 마감은 행사 하루 전(약 이틀 뒤)이라 넉넉하다.
     */
    Long drawnEvent(int winnerLimit, int reserveLimit, int applicants) throws Exception {
        clock.fixed = null;
        Long eventId = closedEventWithApplicants(winnerLimit, reserveLimit, applicants);
        drawWorker.drawDue();
        clock.fixed = NOON;
        jdbc.update("UPDATE fan_meeting_orders SET issued_at = ? WHERE event_id = ?", utc(NOON), eventId);
        return eventId;
    }

    /** 당첨 순번 순서의 주문 번호. */
    List<Long> winnerOrderIds(Long eventId) {
        return jdbc.queryForList("""
                SELECT o.order_id FROM fan_meeting_orders o
                  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
                 WHERE o.event_id = ? AND s.selection_type = 'WINNER' ORDER BY s.selection_rank
                """, Long.class, eventId);
    }

    /** 예비 순번 순서의 선발 상태. */
    List<String> reserveStatuses(Long eventId) {
        return jdbc.queryForList("""
                SELECT status FROM fan_meeting_selections
                 WHERE event_id = ? AND selection_type = 'RESERVE' ORDER BY selection_rank
                """, String.class, eventId);
    }

    /** 예비 순번의 회원. */
    Long reserveUserId(Long eventId, int rank) {
        return jdbc.queryForObject("""
                SELECT user_id FROM fan_meeting_selections
                 WHERE event_id = ? AND selection_type = 'RESERVE' AND selection_rank = ?
                """, Long.class, eventId, rank);
    }

    Long reserveSelectionId(Long eventId, int rank) {
        return jdbc.queryForObject("""
                SELECT selection_id FROM fan_meeting_selections
                 WHERE event_id = ? AND selection_type = 'RESERVE' AND selection_rank = ?
                """, Long.class, eventId, rank);
    }

    /** 승격으로 생긴 주문(예비의 주문). 예비 순번 순서. */
    List<Map<String, Object>> promotedOrders(Long eventId) {
        return jdbc.queryForList("""
                SELECT o.order_id, o.user_id, o.amount, o.status, o.issued_at, s.selection_rank
                  FROM fan_meeting_orders o
                  JOIN fan_meeting_selections s ON s.selection_id = o.selection_id
                 WHERE o.event_id = ? AND s.selection_type = 'RESERVE' ORDER BY s.selection_rank
                """, eventId);
    }

    /** 자리를 비운다: 주문·선발을 만료 상태로. */
    void vacate(Long orderId) {
        setOrder(orderId, "EXPIRED", "EXPIRED");
    }

    /** 결제를 마친 상태로: 주문 PAID, 선발 CONFIRMED. */
    void pay(Long orderId) {
        setOrder(orderId, "PAID", "CONFIRMED");
    }

    /** 결과를 모르는 보류 상태로: 주문 PAYMENT_REVIEW, 선발은 결제 대기 그대로. */
    void review(Long orderId) {
        jdbc.update("""
                UPDATE fan_meeting_orders SET status = 'PAYMENT_REVIEW', review_started_at = ? WHERE order_id = ?
                """, utc(clock.instant()), orderId);
    }

    void setOrder(Long orderId, String orderStatus, String selectionStatus) {
        jdbc.update("UPDATE fan_meeting_orders SET status = ? WHERE order_id = ?", orderStatus, orderId);
        jdbc.update("""
                UPDATE fan_meeting_selections s JOIN fan_meeting_orders o ON o.selection_id = s.selection_id
                   SET s.status = ? WHERE o.order_id = ?
                """, selectionStatus, orderId);
    }

    void setPromotionClosesAt(Long eventId, Instant closesAt) {
        jdbc.update("UPDATE fan_meeting_events SET promotion_closes_at = ? WHERE event_id = ?", utc(closesAt),
                eventId);
    }

    void withdraw(Long userId) {
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = ? WHERE id = ?", utc(Instant.now()), userId);
    }

    String eventStatus(Long eventId) {
        return jdbc.queryForObject("SELECT status FROM fan_meeting_events WHERE event_id = ?", String.class, eventId);
    }

    /** INV-FM-07의 왼쪽: 확정 + 결제 대기. */
    int occupied(Long eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM fan_meeting_selections
                 WHERE event_id = ? AND status IN ('CONFIRMED', 'PAYMENT_PENDING')
                """, Integer.class, eventId);
    }

    static Instant seoul(int hour, int minute, int second) {
        return LocalDate.now(SEOUL).atTime(hour, minute, second).atZone(SEOUL).toInstant();
    }

    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(60);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        TestClock fanMeetingTestClock() {
            return new TestClock();
        }
    }

    /** 세워 둔 시각이 있으면 그 시각, 없으면 실제 시각. */
    static class TestClock extends Clock {
        volatile Instant fixed;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant now = fixed;
            return now != null ? now : Instant.now();
        }
    }
}
