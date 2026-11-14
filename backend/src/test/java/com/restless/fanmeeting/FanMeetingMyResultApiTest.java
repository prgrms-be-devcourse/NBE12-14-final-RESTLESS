package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 내 추첨 결과와 내 주문 조회 (`GET .../result/me`, `GET .../orders/me`).
 * 추첨이 확정되기 전에는 결과를 내보내지 않고, 다른 회원의 결과·주문은 보이지 않는다.
 */
@DisplayName("팬미팅 내 결과·주문 조회")
class FanMeetingMyResultApiTest extends FanMeetingOrderApiSupport {

    // ---- 내 결과 ----

    @Test
    @DisplayName("응모하지 않았거나 응모를 취소했으면 NOT_APPLIED")
    void 응모하지_않았으면_NOT_APPLIED() throws Exception {
        Long eventId = createEvent(true);
        open(eventId);
        String withdrawn = token("USER");
        apply(withdrawn, eventId);
        cancel(withdrawn, eventId);

        assertThat(resultOf(token("USER"), eventId)).isEqualTo(result("NOT_APPLIED", null, null, null, null));
        assertThat(resultOf(withdrawn, eventId)).isEqualTo(result("NOT_APPLIED", null, null, null, null));
    }

    @Test
    @DisplayName("추첨이 확정되기 전(응모 중, 마감, 추첨 중)에는 PENDING만 내보낸다")
    void 추첨_확정_전에는_PENDING() throws Exception {
        Long eventId = createEvent(true, 1, 0);
        open(eventId);
        String bearer = token("USER");
        apply(bearer, eventId);

        assertThat(resultOf(bearer, eventId)).as("응모 중").isEqualTo(result("PENDING", null, null, null, null));

        Instant now = Instant.now();
        setWindow(eventId, "OPEN", now.minus(Duration.ofMinutes(10)), now.minusSeconds(1));
        entryWorker.closeDue();
        assertThat(resultOf(bearer, eventId)).as("마감").isEqualTo(result("PENDING", null, null, null, null));

        // 선발과 주문이 이미 저장됐더라도 행사가 아직 추첨 중이면 결과를 내보내지 않는다.
        drawWorker.drawDue();
        jdbc.update("UPDATE fan_meeting_events SET status = 'DRAWING' WHERE event_id = ?", eventId);
        assertThat(resultOf(bearer, eventId)).as("추첨 중").isEqualTo(result("PENDING", null, null, null, null));
    }

    @Test
    @DisplayName("추첨 뒤: 당첨자는 WINNER + 주문·기한, 예비는 RESERVE + 본인 순번, 나머지는 NOT_SELECTED")
    void 추첨_뒤_당첨_예비_미선발() throws Exception {
        Drawn drawn = drawn();

        Map<String, Object> winner = resultOf(drawn.winner(), drawn.eventId());
        Map<String, Object> reserve = resultOf(drawn.reserve(), drawn.eventId());
        Map<String, Object> notSelected = resultOf(drawn.notSelected(), drawn.eventId());

        assertThat(winner).isEqualTo(result("WINNER", "PAYMENT_PENDING", null, drawn.winnerOrderId(),
                issuedAt(drawn.winnerOrderId()).plus(Duration.ofMinutes(60)).toString()));
        assertThat(reserve).isEqualTo(result("RESERVE", "WAITING", 1, null, null));
        assertThat(notSelected).isEqualTo(result("NOT_SELECTED", null, null, null, null));
    }

    @Test
    @DisplayName("결제를 끝낸 당첨자는 WINNER + CONFIRMED")
    void 결제한_당첨자는_CONFIRMED() throws Exception {
        WinnerOrder order = winnerOrder(0L);
        confirmFree(order.bearer(), order.eventId(), order.orderId());

        Map<String, Object> mine = resultOf(order.bearer(), order.eventId());

        assertThat(mine.get("result")).isEqualTo("WINNER");
        assertThat(mine.get("selectionStatus")).isEqualTo("CONFIRMED");
        assertThat(mine.get("orderId")).isEqualTo(order.orderId().intValue());
    }

    @Test
    @DisplayName("승격된 예비자는 RESERVE 그대로이고, 선발 상태·주문·기한으로 결제 차례임을 안다")
    void 승격된_예비자는_주문이_보인다() throws Exception {
        Drawn drawn = drawn();
        Long reserveUserId = userIdOf(drawn.reserve());
        Map<String, Object> selection = jdbc.queryForMap(
                "SELECT selection_id FROM fan_meeting_selections WHERE event_id = ? AND user_id = ?",
                drawn.eventId(), reserveUserId);
        Long selectionId = ((Number) selection.get("selection_id")).longValue();
        Instant issuedAt = Instant.parse("2026-11-20T03:00:00Z");
        jdbc.update("UPDATE fan_meeting_selections SET status = 'PAYMENT_PENDING' WHERE selection_id = ?",
                selectionId);
        jdbc.update("""
                INSERT INTO fan_meeting_orders (event_id, selection_id, user_id, amount, status, issued_at,
                                                created_at, updated_at)
                VALUES (?, ?, ?, 50000, 'PENDING_PAYMENT', ?, ?, ?)
                """, drawn.eventId(), selectionId, reserveUserId, utc(issuedAt), utc(issuedAt), utc(issuedAt));
        Long orderId = jdbc.queryForObject("SELECT order_id FROM fan_meeting_orders WHERE selection_id = ?",
                Long.class, selectionId);

        assertThat(resultOf(drawn.reserve(), drawn.eventId())).isEqualTo(
                result("RESERVE", "PAYMENT_PENDING", 1, orderId, "2026-11-20T04:00:00Z"));
    }

    @Test
    @DisplayName("없는 행사는 404 FAN_MEETING_NOT_FOUND, 로그인하지 않으면 401")
    void 없는_행사와_로그인_없음() throws Exception {
        Long eventId = createEvent(true);

        MvcResult noEvent = myResult(token("USER"), Long.MAX_VALUE);
        MvcResult noLoginResult = mockMvc.perform(get("/api/fan-meetings/{eventId}/result/me", eventId)).andReturn();
        MvcResult noLoginOrder = mockMvc.perform(get("/api/fan-meetings/{eventId}/orders/me", eventId)).andReturn();

        assertThat(noEvent.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(noEvent)).isEqualTo("FAN_MEETING_NOT_FOUND");
        assertThat(noLoginResult.getResponse().getStatus()).isEqualTo(401);
        assertThat(noLoginOrder.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- 내 주문 ----

    @Test
    @DisplayName("당첨자의 주문: 상태, 금액, 발급 시각, 기한(발급 + 60분), 포기 가능")
    void 당첨자의_주문() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult res = myOrder(order.bearer(), order.eventId());

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        Map<String, Object> body = JsonPath.read(contentOf(res), "$");
        Instant issuedAt = issuedAt(order.orderId());
        assertThat(body).containsOnlyKeys("orderId", "status", "amount", "issuedAt", "paymentDeadline", "paidAt",
                "declinable");
        assertThat(body.get("orderId")).isEqualTo(order.orderId().intValue());
        assertThat(body.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(body.get("amount")).isEqualTo(50000);
        assertThat(Instant.parse((String) body.get("issuedAt"))).isEqualTo(issuedAt);
        assertThat(Instant.parse((String) body.get("paymentDeadline"))).isEqualTo(issuedAt.plus(Duration.ofMinutes(60)));
        assertThat(body.get("paidAt")).isNull();
        assertThat(body.get("declinable")).isEqualTo(true);
    }

    @Test
    @DisplayName("포기 가능 여부: 결제를 준비했거나 기한이 지났거나 결제 대기가 아니면 false")
    void 포기_가능_여부() throws Exception {
        WinnerOrder prepared = winnerOrder();
        WinnerOrder late = winnerOrder();
        WinnerOrder paid = winnerOrder(0L);
        prepare(prepared.bearer(), prepared.orderId(), "prepared");
        setDeadline(late.orderId(), Instant.now().minusSeconds(1));
        confirmFree(paid.bearer(), paid.eventId(), paid.orderId());

        assertThat(orderOf(prepared).get("declinable")).as("결제 준비함").isEqualTo(false);
        assertThat(orderOf(late).get("declinable")).as("기한 지남").isEqualTo(false);
        assertThat(orderOf(paid).get("declinable")).as("결제 완료").isEqualTo(false);
        assertThat(orderOf(paid).get("status")).isEqualTo("PAID");
        assertThat(orderOf(paid).get("paidAt")).isNotNull();
    }

    @Test
    @DisplayName("주문이 없는 회원(예비, 미선발, 미응모)은 404 ORDER_NOT_FOUND. 다른 회원의 주문은 보이지 않는다")
    void 주문이_없으면_404() throws Exception {
        Drawn drawn = drawn();

        for (String bearer : new String[] {drawn.reserve(), drawn.notSelected(), token("USER")}) {
            MvcResult res = myOrder(bearer, drawn.eventId());

            assertThat(res.getResponse().getStatus()).isEqualTo(404);
            assertThat(codeOf(res)).isEqualTo("ORDER_NOT_FOUND");
        }
        MvcResult noEvent = myOrder(drawn.winner(), Long.MAX_VALUE);
        assertThat(noEvent.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(noEvent)).isEqualTo("FAN_MEETING_NOT_FOUND");
    }

    // ---- 도우미 ----

    record Drawn(Long eventId, String winner, Long winnerOrderId, String reserve, String notSelected) {
    }

    /** 응모 3명, 당첨 1명, 예비 1명인 행사를 추첨한다. */
    Drawn drawn() throws Exception {
        Long eventId = closedEventWithApplicants(1, 1, 3);
        drawWorker.drawDue();
        Long winner = jdbc.queryForObject(
                "SELECT user_id FROM fan_meeting_selections WHERE event_id = ? AND selection_type = 'WINNER'",
                Long.class, eventId);
        Long reserve = jdbc.queryForObject(
                "SELECT user_id FROM fan_meeting_selections WHERE event_id = ? AND selection_type = 'RESERVE'",
                Long.class, eventId);
        Long notSelected = jdbc.queryForObject("""
                SELECT a.user_id FROM fan_meeting_applications a
                 WHERE a.event_id = ? AND a.user_id NOT IN (?, ?)
                """, Long.class, eventId, winner, reserve);
        Long orderId = jdbc.queryForObject("SELECT order_id FROM fan_meeting_orders WHERE event_id = ?",
                Long.class, eventId);
        return new Drawn(eventId, bearerOf(winner), orderId, bearerOf(reserve), bearerOf(notSelected));
    }

    Map<String, Object> resultOf(String bearer, Long eventId) throws Exception {
        MvcResult res = myResult(bearer, eventId);
        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        return JsonPath.read(contentOf(res), "$");
    }

    Map<String, Object> orderOf(WinnerOrder order) throws Exception {
        MvcResult res = myOrder(order.bearer(), order.eventId());
        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        return JsonPath.read(contentOf(res), "$");
    }

    /** 응답 본문 전체. 뽑히지 않았으면 result 말고는 모두 null이다. */
    static Map<String, Object> result(String result, String selectionStatus, Integer reserveRank, Long orderId,
                                      String paymentDeadline) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("result", result);
        expected.put("selectionStatus", selectionStatus);
        expected.put("reserveRank", reserveRank);
        expected.put("orderId", orderId == null ? null : orderId.intValue());
        expected.put("paymentDeadline", paymentDeadline);
        return expected;
    }
}
