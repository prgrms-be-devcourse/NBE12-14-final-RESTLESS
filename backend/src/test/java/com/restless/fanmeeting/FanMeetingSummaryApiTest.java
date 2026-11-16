package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/** 관리자 summary `GET /api/admin/fan-meetings/{eventId}/summary`. 행사 진행 상황을 한 번에 본다. */
@DisplayName("팬미팅 관리자 summary")
class FanMeetingSummaryApiTest extends FanMeetingPromotionSupport {

    @Test
    @DisplayName("C41 관리자만 볼 수 있다: 일반 회원·주최자 403 FORBIDDEN, 로그인 안 함 401")
    void 관리자만_볼_수_있다() throws Exception {
        Long eventId = createEvent(true);

        summary(token("USER"), eventId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        summary(token("ORGANIZER"), eventId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/admin/fan-meetings/{eventId}/summary", eventId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 행사 → 404 FAN_MEETING_NOT_FOUND")
    void 없는_행사는_404() throws Exception {
        summary(token("ADMIN"), 999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));
    }

    @Test
    @DisplayName("추첨 전 행사: 응모 수만 있고 나머지는 0, 승격 순번은 null, 목록은 비어 있다")
    void 추첨_전_행사() throws Exception {
        Long eventId = createEvent(true, 3, 2);
        open(eventId);
        apply(token("USER"), eventId).andExpect(status().isCreated());
        String withdrawn = token("USER");
        apply(withdrawn, eventId).andExpect(status().isCreated());
        cancel(withdrawn, eventId).andExpect(status().isNoContent());

        summary(token("ADMIN"), eventId).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.winnerLimit").value(3))
                .andExpect(jsonPath("$.reserveLimit").value(2))
                .andExpect(jsonPath("$.promotionClosesAt").isNotEmpty())
                .andExpect(jsonPath("$.applicationCount").value(1))
                .andExpect(jsonPath("$.occupied").value(0))
                .andExpect(jsonPath("$.selectionCounts.PAYMENT_PENDING").value(0))
                .andExpect(jsonPath("$.selectionCounts.NOT_NEEDED").value(0))
                .andExpect(jsonPath("$.orderCounts.PENDING_PAYMENT").value(0))
                .andExpect(jsonPath("$.orderCounts.REFUNDED").value(0))
                .andExpect(jsonPath("$.maxPromotedReserveRank").isEmpty())
                .andExpect(jsonPath("$.reviews").isEmpty())
                .andExpect(jsonPath("$.refundPending").isEmpty());
    }

    @Test
    @DisplayName("결제 진행 중: 상태별 인원, 정원 점유, 승격된 예비 최대 순번, 보류 목록(경과 분), 환불 대기 목록. 회원 ID는 없다")
    void 결제_진행_중인_행사() throws Exception {
        Long eventId = drawnEvent(4, 3, 8);
        List<Long> winners = winnerOrderIds(eventId);
        pay(winners.get(0));
        review(winners.get(1));
        vacate(winners.get(2));
        setOrder(winners.get(3), "REFUND_PENDING", "EXPIRED");
        promotionWorker.promoteDue();
        Instant reviewStartedAt = NOON;
        clock.fixed = NOON.plus(Duration.ofMinutes(42)).plusSeconds(59);

        String body = summary(token("ADMIN"), eventId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_IN_PROGRESS"))
                .andExpect(jsonPath("$.applicationCount").value(8))
                .andExpect(jsonPath("$.occupied").value(4))
                .andExpect(jsonPath("$.selectionCounts.CONFIRMED").value(1))
                .andExpect(jsonPath("$.selectionCounts.PAYMENT_PENDING").value(3))
                .andExpect(jsonPath("$.selectionCounts.EXPIRED").value(2))
                .andExpect(jsonPath("$.selectionCounts.WAITING").value(1))
                .andExpect(jsonPath("$.selectionCounts.DECLINED").value(0))
                .andExpect(jsonPath("$.selectionCounts.NOT_NEEDED").value(0))
                .andExpect(jsonPath("$.orderCounts.PAID").value(1))
                .andExpect(jsonPath("$.orderCounts.PAYMENT_REVIEW").value(1))
                .andExpect(jsonPath("$.orderCounts.PENDING_PAYMENT").value(2))
                .andExpect(jsonPath("$.orderCounts.EXPIRED").value(1))
                .andExpect(jsonPath("$.orderCounts.REFUND_PENDING").value(1))
                .andExpect(jsonPath("$.orderCounts.CANCELLED").value(0))
                .andExpect(jsonPath("$.maxPromotedReserveRank").value(2))
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].orderId").value(winners.get(1)))
                .andExpect(jsonPath("$.reviews[0].reviewStartedAt").value(reviewStartedAt.toString()))
                .andExpect(jsonPath("$.reviews[0].elapsedMinutes").value(42))
                .andExpect(jsonPath("$.refundPending.length()").value(1))
                .andExpect(jsonPath("$.refundPending[0].orderId").value(winners.get(3)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("userId");
    }

    @Test
    @DisplayName("승격 차례에 탈퇴가 확인된 예비(NOT_NEEDED)는 승격된 순번으로 세지 않는다")
    void 탈퇴한_예비는_승격_순번에_넣지_않는다() throws Exception {
        Long eventId = drawnEvent(1, 3, 4);
        vacate(winnerOrderIds(eventId).get(0));
        withdraw(reserveUserId(eventId, 1));
        withdraw(reserveUserId(eventId, 2));
        promotionWorker.promoteDue();

        summary(token("ADMIN"), eventId).andExpect(jsonPath("$.maxPromotedReserveRank").isEmpty())
                .andExpect(jsonPath("$.selectionCounts.NOT_NEEDED").value(2));

        promotionWorker.promoteDue();

        summary(token("ADMIN"), eventId).andExpect(jsonPath("$.maxPromotedReserveRank").value(3));
    }

    ResultActions summary(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(get("/api/admin/fan-meetings/{eventId}/summary", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }
}
