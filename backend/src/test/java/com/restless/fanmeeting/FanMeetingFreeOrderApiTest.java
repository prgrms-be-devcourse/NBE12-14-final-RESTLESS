package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 0원 주문 (`transactions.md` 6절). 공통 결제는 0원을 `FREE_ORDER`로 거절하므로,
 * 손님이 기한 안에 참가 확정을 누르면 결제 없이 확정한다. 누르지 않으면 유료 주문처럼 만료된다.
 */
@DisplayName("팬미팅 0원 주문 확정")
class FanMeetingFreeOrderApiTest extends FanMeetingOrderApiSupport {

    @Test
    @DisplayName("0원 주문은 공통 결제로 준비할 수 없다(FREE_ORDER). 결제 준비 기록도 남지 않는다")
    void 공통_결제는_0원을_거절한다() throws Exception {
        WinnerOrder order = winnerOrder(0L);

        Prepared p = prepare(order.bearer(), order.orderId(), "free");

        assertThat(p.status()).as(p.body()).isEqualTo(409);
        assertThat((String) JsonPath.read(p.body(), "$.details.reason")).isEqualTo("FREE_ORDER");
        assertThat(orderColumn(order.orderId(), "attempt_requested_at")).isNull();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("0원 주문을 기한 안에 확정하면 결제 없이 주문 PAID, 선발 CONFIRMED")
    void 기한_안에_확정하면_결제_없이_확정된다() throws Exception {
        WinnerOrder order = winnerOrder(0L);

        MvcResult res = confirmFree(order.bearer(), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        assertThat((String) JsonPath.read(contentOf(res), "$.status")).isEqualTo("PAID");
        assertThat(((Number) JsonPath.read(contentOf(res), "$.amount")).longValue()).isZero();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(selectionStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(orderColumn(order.orderId(), "paid_at")).isNotNull();
        assertThat(orderColumn(order.orderId(), "payment_id")).as("결제 시도가 없다").isNull();
        assertThat(orderColumn(order.orderId(), "approved_attempt_id")).isNull();
    }

    @Test
    @DisplayName("이미 확정한 0원 주문을 다시 확정해도 200이고 아무것도 바뀌지 않는다")
    void 두_번_확정해도_한_번만_반영한다() throws Exception {
        WinnerOrder order = winnerOrder(0L);
        confirmFree(order.bearer(), order.eventId(), order.orderId());
        Object paidAt = orderColumn(order.orderId(), "paid_at");
        Object updatedAt = orderColumn(order.orderId(), "updated_at");

        MvcResult again = confirmFree(order.bearer(), order.eventId(), order.orderId());

        assertThat(again.getResponse().getStatus()).as(contentOf(again)).isEqualTo(200);
        assertThat(orderColumn(order.orderId(), "paid_at")).isEqualTo(paidAt);
        assertThat(orderColumn(order.orderId(), "updated_at")).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("참가비가 있는 주문은 확정 API로 확정할 수 없다 → 409 ORDER_NOT_PAYABLE")
    void 유료_주문은_확정_API로_확정할_수_없다() throws Exception {
        WinnerOrder order = winnerOrder();

        MvcResult res = confirmFree(order.bearer(), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(res)).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(order.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("기한이 지난 0원 주문은 확정할 수 없다(409 PAYMENT_WINDOW_EXPIRED). 기한 전이면 된다")
    void 기한이_지나면_확정할_수_없다() throws Exception {
        WinnerOrder before = winnerOrder(0L);
        WinnerOrder after = winnerOrder(0L);
        setDeadline(before.orderId(), Instant.now().plusSeconds(5));
        setDeadline(after.orderId(), Instant.now().minusSeconds(1));

        MvcResult ok = confirmFree(before.bearer(), before.eventId(), before.orderId());
        MvcResult late = confirmFree(after.bearer(), after.eventId(), after.orderId());

        assertThat(ok.getResponse().getStatus()).as(contentOf(ok)).isEqualTo(200);
        assertThat(late.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(late)).isEqualTo("PAYMENT_WINDOW_EXPIRED");
        assertThat(orderStatus(after.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(selectionStatus(after.orderId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("다른 회원·다른 행사 주소로는 확정할 수 없다 → 404 ORDER_NOT_FOUND")
    void 다른_회원과_다른_행사는_404() throws Exception {
        WinnerOrder order = winnerOrder(0L);
        WinnerOrder other = winnerOrder(0L);

        MvcResult stranger = confirmFree(token("USER"), order.eventId(), order.orderId());
        MvcResult wrongEvent = confirmFree(order.bearer(), other.eventId(), order.orderId());

        assertThat(stranger.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(stranger)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(wrongEvent.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(wrongEvent)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(orderStatus(other.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("포기한 0원 주문, 결제 진행 중이 아닌 행사의 0원 주문은 확정할 수 없다 → 409 ORDER_NOT_PAYABLE")
    void 포기했거나_행사가_끝났으면_확정할_수_없다() throws Exception {
        WinnerOrder declined = winnerOrder(0L);
        decline(declined.bearer(), declined.eventId(), declined.orderId());
        WinnerOrder completed = winnerOrder(0L);
        jdbc.update("UPDATE fan_meeting_events SET status = 'RECRUITMENT_COMPLETED' WHERE event_id = ?",
                completed.eventId());

        MvcResult afterDecline = confirmFree(declined.bearer(), declined.eventId(), declined.orderId());
        MvcResult afterComplete = confirmFree(completed.bearer(), completed.eventId(), completed.orderId());

        assertThat(afterDecline.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(afterDecline)).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(orderStatus(declined.orderId())).isEqualTo("CANCELLED");
        assertThat(afterComplete.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(afterComplete)).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(orderStatus(completed.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("0원 주문도 확정 전에는 포기할 수 있다")
    void 확정_전에는_포기할_수_있다() throws Exception {
        WinnerOrder order = winnerOrder(0L);

        MvcResult res = decline(order.bearer(), order.eventId(), order.orderId());

        assertThat(res.getResponse().getStatus()).as(contentOf(res)).isEqualTo(200);
        assertThat(orderStatus(order.orderId())).isEqualTo("CANCELLED");
        assertThat(selectionStatus(order.orderId())).isEqualTo("DECLINED");
    }
}
