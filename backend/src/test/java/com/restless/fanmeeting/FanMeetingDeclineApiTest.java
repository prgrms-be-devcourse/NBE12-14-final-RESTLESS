package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.restless.payment.infrastructure.fake.FakePgClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 결제 포기 (`transactions.md` 9절). 결제를 준비한 적 없는 결제 대기 주문만, 기한 전에만 포기할 수 있다. */
@DisplayName("팬미팅 결제 포기")
class FanMeetingDeclineApiTest extends FanMeetingOrderApiSupport {

    @Autowired FakePgClient fakePg;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    @Test
    @DisplayName("C28 결제 준비 전 포기 → 주문 CANCELLED, 선발 DECLINED")
    void 결제_준비_전에는_포기할_수_있다() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult res = decline(order.bearer(), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        assertThat((String) JsonPath.read(contentOf(res), "$.status")).isEqualTo("CANCELLED");
        assertThat((Boolean) JsonPath.read(contentOf(res), "$.declinable")).isFalse();
        assertThat(orderStatus(order.orderId())).isEqualTo("CANCELLED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("DECLINED");
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).as("INV-FM-17").isNull();
    }

    @Test
    @DisplayName("C30 결제를 준비한 주문은 포기할 수 없다. 그 시도가 실패로 끝났어도 같다")
    void 결제를_준비한_주문은_포기할_수_없다() throws Exception {
        WinnerOrder prepared = winnerOrder();
        WinnerOrder failed = winnerOrder();
        prepare(prepared.bearer(), prepared.orderId(), "prepared");
        Prepared p = prepare(failed.bearer(), failed.orderId(), "failed");
        fakePg.willReject(p.pgOrderId(), "REJECT_CARD_COMPANY");
        confirm(failed.bearer(), p);
        assertThat(orderColumn(failed.orderId(), "payment_id")).as("실패한 시도는 비운다").isNull();

        for (WinnerOrder order : List.of(prepared, failed)) {
            MvcResult res = decline(order.bearer(), order.eventId(), order.orderId());

            assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(409);
            assertThat(codeOf(res)).isEqualTo("DECLINE_NOT_ALLOWED");
            assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
            assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
        }
    }

    @Test
    @DisplayName("C26 다른 회원의 주문은 포기할 수 없다 → 404, 상태 변화 없음")
    void 다른_회원의_주문은_404() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult res = decline(token("USER"), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("C42 다른 행사의 eventId + 내 orderId → 404 ORDER_NOT_FOUND, 어느 쪽도 변화 없음")
    void 다른_행사_주소로는_포기할_수_없다() throws Exception {
        WinnerOrder mine = winnerOrder();
        WinnerOrder other = winnerOrder();

        MvcResult res = decline(mine.bearer(), other.eventId(), mine.orderId());

        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(orderStatus(mine.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(orderStatus(other.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("없는 주문은 404 ORDER_NOT_FOUND, 없는 행사는 404 FAN_MEETING_NOT_FOUND")
    void 없는_주문과_없는_행사() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult noOrder = decline(order.bearer(), order.eventId(), Long.MAX_VALUE);
        MvcResult noEvent = decline(order.bearer(), Long.MAX_VALUE, order.orderId());

        assertThat(codeOf(noOrder)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(noOrder.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(noEvent)).isEqualTo("FAN_MEETING_NOT_FOUND");
        assertThat(noEvent.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문(결제 완료, 이미 포기)은 409 ORDER_NOT_PAYABLE")
    void 결제_대기가_아니면_포기할_수_없다() throws Exception {
        WinnerOrder paid = winnerOrder();
        jdbc.update("UPDATE fan_meeting_orders SET status = 'PAID' WHERE order_id = ?", paid.orderId());
        jdbc.update("""
                UPDATE fan_meeting_selections s JOIN fan_meeting_orders o ON o.selection_id = s.selection_id
                   SET s.status = 'CONFIRMED' WHERE o.order_id = ?
                """, paid.orderId());
        WinnerOrder declined = winnerOrder();
        decline(declined.bearer(), declined.eventId(), declined.orderId());

        MvcResult paidRes = decline(paid.bearer(), paid.eventId(), paid.orderId());
        MvcResult again = decline(declined.bearer(), declined.eventId(), declined.orderId());

        assertThat(paidRes.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(paidRes)).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(orderStatus(paid.orderId())).isEqualTo("PAID");
        assertThat(selectionStatus(paid.orderId())).isEqualTo("CONFIRMED");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo("ORDER_NOT_PAYABLE");
    }

    @Test
    @DisplayName("기한이 지난 주문은 포기할 수 없다(409 PAYMENT_WINDOW_EXPIRED). 기한 전이면 된다")
    void 기한이_지나면_포기할_수_없다() throws Exception {
        WinnerOrder before = winnerOrder();
        WinnerOrder after = winnerOrder();
        setDeadline(before.orderId(), Instant.now().plusSeconds(5));
        setDeadline(after.orderId(), Instant.now().minusSeconds(1));

        MvcResult ok = decline(before.bearer(), before.eventId(), before.orderId());
        MvcResult late = decline(after.bearer(), after.eventId(), after.orderId());

        assertThat(ok.getResponse().getStatus()).as(contentOf(ok)).isEqualTo(200);
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("PAYMENT_WINDOW_EXPIRED");
        assertThat(orderStatus(after.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(after.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("포기한 주문은 결제를 준비할 수 없다")
    void 포기한_주문은_결제할_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();
        decline(order.bearer(), order.eventId(), order.orderId());

        Prepared p = prepare(order.bearer(), order.orderId(), "after-decline");

        assertThat(p.status()).as(p.body()).isEqualTo(409);
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).isNull();
    }

    @Test
    @DisplayName("C29 포기와 결제 준비가 동시에 오면 하나만 성공한다 (INV-FM-17)")
    void 포기와_결제_준비가_동시에_오면_하나만_성공한다() throws Exception {
        for (int round = 0; round < 5; round++) {
            WinnerOrder order = winnerOrder();
            String key = "race-" + round;

            AtomicInteger turn = new AtomicInteger();
            List<Integer> statuses = concurrently(2, () -> turn.getAndIncrement() == 0
                    ? decline(order.bearer(), order.eventId(), order.orderId()).getResponse().getStatus()
                    : prepare(order.bearer(), order.orderId(), key).status());

            String status = orderStatus(order.orderId());
            Object requestedAt = orderColumn(order.orderId(), "attempt_requested_at");
            assertThat(statuses).as("하나는 성공, 하나는 409").containsExactlyInAnyOrder(
                    "CANCELLED".equals(status) ? 200 : 201, 409);
            if ("CANCELLED".equals(status)) {
                assertThat(requestedAt).as("INV-FM-17 포기한 주문은 결제 준비 기록이 없다").isNull();
                assertThat(selectionStatus(order.orderId())).isEqualTo("DECLINED");
            } else {
                assertThat(status).isEqualTo("PENDING_PAYMENT");
                assertThat(requestedAt).isNotNull();
                assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
            }
        }
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void 로그인하지_않으면_401() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult res = mockMvc.perform(MockMvcRequestBuilders
                .post("/api/fan-meetings/{eventId}/orders/{orderId}/decline", order.eventId(), order.orderId()))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }
}
