package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.payment.application.PaymentReconciler;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.infrastructure.fake.FakePgClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 관통 테스트 (C72): 행사 생성부터 모집 완료까지 한 번에 이어서 돌리고, 매 단계 뒤에 불변식을 SQL로 확인한다.
 *
 * <p>상태를 DB로 직접 고치지 않는다. API를 부르고, 시계를 옮기고, 워커와 미완료 결제 재확인 작업을 직접 부른다(스케줄러는 꺼져 있다).
 * DB는 검증하려고 읽기만 한다. 미완료 결제 재확인 작업은 결제 모듈의 {@link PaymentReconciler#runOnce()}다: 결제 결과를 모르는 시도를
 * PG에 다시 물어 확정하고, 그 결과를 팬미팅 Handler로 알려 준다.
 */
@DisplayName("팬미팅 관통: 행사 생성부터 모집 완료까지")
@ExtendWith(OutputCaptureExtension.class)
class FanMeetingWalkthroughTest extends FanMeetingPromotionSupport {

    /** 줄거리가 시작하는 날(내일). 시각은 모두 Asia/Seoul. */
    static final LocalDate DAY = NOON.atZone(SEOUL).toLocalDate();

    @Autowired PaymentReconciler reconciler;
    @Autowired FakePgClient fakePg;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    @Test
    @DisplayName("C72 정원 3, 예비 4, 응모 8: 결제, 포기, 보류의 두 결말(재확인 작업이 승인 확인 / 실패 확정), 만료, 승격 3번, 야간, 모집 완료")
    void 행사_생성부터_모집_완료까지(CapturedOutput output) throws Exception {
        // ── 첫날 09:30 행사 생성: 오픈 10:00, 마감 10:10, 승격 마감은 모레 18:00 ─────────────────────
        clock.fixed = at(0, 9, 30);
        Long eventId = createEvent(3, 4, 50000L, at(0, 10, 0), at(4, 10, 10), at(2, 18, 0));
        List<Long> events = List.of(eventId);
        assertThat(eventStatus(eventId)).isEqualTo("SCHEDULED");

        // ── 10:00 오픈, 8명 응모 ────────────────────────────────────────────────────────────────
        clock.fixed = at(0, 10, 0);
        entryWorker.openDue();
        assertThat(eventStatus(eventId)).isEqualTo("OPEN");
        List<String> applicants = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String bearer = token("USER");
            apply(bearer, eventId).andExpect(status().isCreated());
            applicants.add(bearer);
        }
        result(applicants.get(0), eventId).andExpect(jsonPath("$.result").value("PENDING"));

        // ── 10:10 마감, 추첨: 당첨 3(결제 대기 + 주문), 예비 4(대기), 미선발 1 ──────────────────────
        clock.fixed = at(0, 10, 10);
        entryWorker.closeDue();
        assertThat(eventStatus(eventId)).isEqualTo("CLOSED");
        apply(token("USER"), eventId).andExpect(status().isConflict());
        drawWorker.drawDue();
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        List<Long> winners = winnerOrderIds(eventId);
        assertThat(winners).hasSize(3);
        assertThat(reserveStatuses(eventId)).containsExactly("WAITING", "WAITING", "WAITING", "WAITING");
        assertThat(occurrences(output, "type=WINNER eventId=" + eventId + " ")).isEqualTo(3);
        check(events, "추첨 뒤");
        Long w1 = winners.get(0);
        Long w2 = winners.get(1);
        Long w3 = winners.get(2);

        // ── 10:20 당첨 1 결제, 당첨 2 포기, 당첨 3은 PG 응답이 사라져 보류 ────────────────────────
        clock.fixed = at(0, 10, 20);
        Prepared p1 = prepare(bearerOfOrder(w1), w1, "walk-w1");
        MvcResult tooLate = decline(bearerOfOrder(w1), eventId, w1);
        assertThat(tooLate.getResponse().getStatus()).as("결제창을 연 뒤에는 포기할 수 없다 (INV-FM-17)").isEqualTo(409);
        assertThat(codeOf(tooLate)).isEqualTo("DECLINE_NOT_ALLOWED");
        fakePg.willApprove(p1.pgOrderId());
        assertThat(confirm(bearerOfOrder(w1), p1).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(w1)).isEqualTo("PAID");

        MvcResult declined = decline(bearerOfOrder(w2), eventId, w2);
        assertThat(declined.getResponse().getStatus()).as(contentOf(declined)).isEqualTo(200);
        assertThat(orderStatus(w2)).isEqualTo("CANCELLED");

        Prepared p3 = prepare(bearerOfOrder(w3), w3, "walk-w3");
        fakePg.willLoseResponse(p3.pgOrderId());
        confirm(bearerOfOrder(w3), p3);
        assertThat(orderStatus(w3)).as("PG는 승인했지만 답을 못 받았다 → 보류").isEqualTo("PAYMENT_REVIEW");
        assertThat(selectionStatus(w3)).as("보류 중에도 자리를 지킨다").isEqualTo("PAYMENT_PENDING");
        check(events, "첫 결제들 뒤");

        // 승격: 포기한 자리 하나만 예비 1번에게. 보류 자리는 넘기지 않는다.
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("PAYMENT_PENDING", "WAITING", "WAITING", "WAITING");
        Long r1 = promotedOrderId(eventId, 1);
        assertThat(deadline(r1)).isEqualTo(at(0, 11, 20));
        result(bearerOfOrder(r1), eventId).andExpect(jsonPath("$.result").value("RESERVE"))
                .andExpect(jsonPath("$.selectionStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.orderId").value(r1));
        check(events, "첫 승격 뒤");

        // ── 10:21 재확인 작업: PG에 다시 물으니 당첨 3은 승인돼 있었다 → 참가 확정 ────────────────────────
        clock.fixed = at(0, 10, 21);
        reconciler.runOnce();
        assertThat(orderStatus(w3)).isEqualTo("PAID");
        assertThat(selectionStatus(w3)).isEqualTo("CONFIRMED");
        check(events, "재확인 작업이 승인을 확인한 뒤");

        // ── 11:20 예비 1번이 결제하지 않고 기한(11:20)이 됐다 → 만료, 예비 2번 승격 ────────────────
        clock.fixed = at(0, 11, 20);
        expiryWorker.expireDue();
        assertThat(orderStatus(r1)).isEqualTo("EXPIRED");
        check(events, "예비 1번 만료 뒤");
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("EXPIRED", "PAYMENT_PENDING", "WAITING", "WAITING");
        Long r2 = promotedOrderId(eventId, 2);
        check(events, "두 번째 승격 뒤");

        // ── 11:30 예비 2번 결제 중 PG가 답하지 않는다 → 보류 ──────────────────────────────────────
        clock.fixed = at(0, 11, 30);
        Prepared p5 = prepare(bearerOfOrder(r2), r2, "walk-r2");
        fakePg.willTimeout(p5.pgOrderId());
        confirm(bearerOfOrder(r2), p5);
        assertThat(orderStatus(r2)).isEqualTo("PAYMENT_REVIEW");

        // 11:31 재확인 작업이 물어도 PG에 기록이 없다. 아직 15분이 안 지나 실패로 단정하지 않는다.
        clock.fixed = at(0, 11, 31);
        reconciler.runOnce();
        assertThat(orderStatus(r2)).isEqualTo("PAYMENT_REVIEW");

        // ── 12:25 기한(12:20)이 지났지만 보류라서 만료하지 않고, 자리도 넘기지 않는다. 30분 경고 1회 ──
        clock.fixed = at(0, 12, 25);
        expiryWorker.expireDue();
        expiryWorker.warnStaleReviews();
        expiryWorker.warnStaleReviews();
        promotionWorker.promoteDue();
        assertThat(orderStatus(r2)).as("INV-FM-11 보류 자리는 만료되지 않는다").isEqualTo("PAYMENT_REVIEW");
        assertThat(reserveStatuses(eventId)).containsExactly("EXPIRED", "PAYMENT_PENDING", "WAITING", "WAITING");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(occurrences(output, "FANMEETING_PAYMENT_REVIEW_STALE orderId=" + r2 + " ")).isEqualTo(1);
        check(events, "보류가 기한을 넘긴 뒤");

        // ── 23:30 재확인 작업이 실패로 확정한다(15분 넘게 PG에 기록 없음). 기한이 지났으므로 만료. 밤이라 승격은 안 한다 ──
        clock.fixed = at(0, 23, 30);
        reconciler.runOnce();
        assertThat(orderStatus(r2)).isEqualTo("EXPIRED");
        assertThat(selectionStatus(r2)).isEqualTo("EXPIRED");
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).as("야간에는 승격하지 않는다")
                .containsExactly("EXPIRED", "EXPIRED", "WAITING", "WAITING");
        assertThat(eventStatus(eventId)).isEqualTo("PAYMENT_IN_PROGRESS");
        check(events, "밤");

        // ── 다음 날 09:00 예비 3번 승격, 09:10 결제 → 정원이 다 찼다 ──────────────────────────────
        clock.fixed = at(1, 9, 0);
        promotionWorker.promoteDue();
        assertThat(reserveStatuses(eventId)).containsExactly("EXPIRED", "EXPIRED", "PAYMENT_PENDING", "WAITING");
        Long r3 = promotedOrderId(eventId, 3);
        check(events, "아침 승격 뒤");

        clock.fixed = at(1, 9, 10);
        Prepared p6 = prepare(bearerOfOrder(r3), r3, "walk-r3");
        fakePg.willApprove(p6.pgOrderId());
        assertThat(confirm(bearerOfOrder(r3), p6).getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(r3)).isEqualTo("PAID");

        // ── 모집 완료: 남은 예비 4번은 NOT_NEEDED ────────────────────────────────────────────────
        promotionWorker.promoteDue();
        assertThat(eventStatus(eventId)).isEqualTo("RECRUITMENT_COMPLETED");
        assertThat(reserveStatuses(eventId)).containsExactly("EXPIRED", "EXPIRED", "CONFIRMED", "NOT_NEEDED");
        check(events, "모집 완료 뒤");

        // 승격 알림은 승격한 3번만, 관리자 summary도 같은 그림이어야 한다.
        assertThat(occurrences(output, "type=PROMOTION eventId=" + eventId + " ")).isEqualTo(3);
        mockMvc.perform(get("/api/admin/fan-meetings/{eventId}/summary", eventId)
                        .header(HttpHeaders.AUTHORIZATION, token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECRUITMENT_COMPLETED"))
                .andExpect(jsonPath("$.applicationCount").value(8))
                .andExpect(jsonPath("$.occupied").value(3))
                .andExpect(jsonPath("$.selectionCounts.CONFIRMED").value(3))
                .andExpect(jsonPath("$.selectionCounts.EXPIRED").value(2))
                .andExpect(jsonPath("$.selectionCounts.DECLINED").value(1))
                .andExpect(jsonPath("$.selectionCounts.NOT_NEEDED").value(1))
                .andExpect(jsonPath("$.selectionCounts.PAYMENT_PENDING").value(0))
                .andExpect(jsonPath("$.selectionCounts.WAITING").value(0))
                .andExpect(jsonPath("$.orderCounts.PAID").value(3))
                .andExpect(jsonPath("$.orderCounts.EXPIRED").value(2))
                .andExpect(jsonPath("$.orderCounts.CANCELLED").value(1))
                .andExpect(jsonPath("$.maxPromotedReserveRank").value(3))
                .andExpect(jsonPath("$.reviews").isEmpty());

        // 끝난 행사는 워커와 재확인 작업을 더 돌려도 그대로다.
        clock.fixed = at(2, 18, 0);
        reconciler.runOnce();
        expiryWorker.expireDue();
        promotionWorker.promoteDue();
        assertThat(occupied(eventId)).isEqualTo(3);
        check(events, "끝난 뒤 한 번 더");
    }

    /** 무작위 시나리오에서 시간이 흐르는 순서: 기한 뒤, 밤, 아침, …, 승격 마감 30분 전(새 기한이 마감을 넘어 승격 불가),
     * 마지막은 승격 마감 시각.
     */
    static final List<Instant> SWEEP_TIMES = List.of(at(0, 11, 11), at(0, 12, 12), at(0, 23, 30), at(1, 9, 0),
            at(1, 10, 1), at(1, 11, 2), at(1, 23, 10), at(2, 9, 30), at(2, 17, 30), at(2, 18, 0));

    @Test
    @DisplayName("C73 무작위 시나리오(시드 고정): 행사 10개에 포기·0원 확정·승인·거절·결과 모름·늦은 승인·예비 탈퇴를 섞고, "
            + "회차마다 재확인 작업 → 만료 → 승격을 돌려 불변식을 확인한다. 승격 마감 뒤에는 모든 행사가 모집 완료")
    void 무작위_시나리오에서도_불변식을_지킨다() throws Exception {
        for (long seed : new long[] {20261115L, 1L, 2L, 3L}) {
            fakePg.reset();
            sweep(seed);
        }
    }

    private void sweep(long seed) throws Exception {
        Random random = new Random(seed);
        Map<String, Integer> actions = new TreeMap<>();
        String label = "시드 " + seed;

        clock.fixed = at(0, 9, 30);
        Map<Long, Long> feeByEvent = new LinkedHashMap<>();
        Map<Long, Integer> applicantsByEvent = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            // 첫 행사는 예비를 넉넉히 둔다. 승격 마감 직전까지 예비가 남아 있어야 "마감 60분 전부터는 승격하지 않는다"를 볼 수 있다.
            int winners = i == 0 ? 2 : 1 + random.nextInt(4);
            int reserves = i == 0 ? 30 : random.nextInt(9);
            long fee = random.nextInt(3) == 0 ? 0L : 50000L;
            Long eventId = createEvent(winners, reserves, fee, at(0, 10, 0), at(4, 10, 10), at(2, 18, 0));
            feeByEvent.put(eventId, fee);
            applicantsByEvent.put(eventId, Math.max(1, winners + reserves + random.nextInt(4) - 1));
        }
        List<Long> events = List.copyOf(feeByEvent.keySet());

        clock.fixed = at(0, 10, 0);
        entryWorker.openDue();
        for (Long eventId : events) {
            for (int i = 0; i < applicantsByEvent.get(eventId); i++) {
                apply(token("USER"), eventId).andExpect(status().isCreated());
            }
        }
        clock.fixed = at(0, 10, 10);
        entryWorker.closeDue();
        drawWorker.drawDue();
        check(events, label + ", 추첨 뒤");

        clock.fixed = at(0, 10, 15);
        for (int round = 0; round < SWEEP_TIMES.size(); round++) {
            for (Long eventId : events) {
                for (Long orderId : payableOrderIds(eventId)) {
                    String action = act(random, eventId, orderId, feeByEvent.get(eventId), seed + "-" + round);
                    actions.merge(action, 1, Integer::sum);
                }
                if (random.nextInt(5) == 0) {
                    List<Long> waiting = jdbc.queryForList(
                            "SELECT user_id FROM fan_meeting_selections WHERE event_id = ? AND status = 'WAITING'",
                            Long.class, eventId);
                    if (!waiting.isEmpty()) {
                        // 탈퇴만은 회원 테이블을 직접 고친다(회원 모듈의 상태라 팬미팅 API로 만들 수 없다).
                        withdraw(waiting.get(random.nextInt(waiting.size())));
                        actions.merge("예비 탈퇴", 1, Integer::sum);
                    }
                }
            }
            String when = label + ", " + (round + 1) + "회차";
            check(events, when + " 행동 뒤");

            clock.fixed = SWEEP_TIMES.get(round);
            reconciler.runOnce();
            check(events, when + " 재확인 작업 뒤");
            expiryWorker.expireDue();
            check(events, when + " 만료 뒤");
            promotionWorker.promoteDue();
            check(events, when + " 승격 뒤");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM fan_meeting_selections s JOIN users u ON u.id = s.user_id
                     WHERE s.event_id IN (%s) AND u.status = 'WITHDRAWN' AND s.status NOT IN ('WAITING', 'NOT_NEEDED')
                    """.formatted(events.stream().map(String::valueOf).collect(Collectors.joining(","))),
                    Integer.class)).as("%s: 탈퇴한 예비가 승격됨", when).isZero();
        }

        // 승격 마감이 지났고 재확인 작업이 보류를 모두 풀었으므로, 모든 행사가 끝나 있어야 한다.
        for (Long eventId : events) {
            assertThat(eventStatus(eventId)).as("%s, 행사 %d (행동: %s)", label, eventId, actions)
                    .isEqualTo("RECRUITMENT_COMPLETED");
        }
        assertThat(actions).as("%s: 주요 행동이 한 번 이상 실행돼야 한다", label)
                .containsKeys("포기", "승인", "결과 모름(PG 기록 없음)", "결과 모름(응답 유실)", "늦은 승인");
    }

    /** 결제 대기이고 진행 중인 시도가 없는 주문 하나에 무작위 행동을 한다. 실행한 행동 이름을 돌려준다. */
    private String act(Random random, Long eventId, Long orderId, long fee, String key) throws Exception {
        String bearer = bearerOfOrder(orderId);
        if (fee == 0) {
            return switch (random.nextInt(3)) {
                case 0 -> expect(decline(bearer, eventId, orderId), 200, "포기");
                case 1 -> expect(confirmFree(bearer, eventId, orderId), 200, "0원 확정");
                default -> "아무것도 안 함";
            };
        }
        int pick = random.nextInt(9);
        if (pick == 0) {
            // 결제창을 연 적이 있는 주문은 포기할 수 없다(409). 연 적이 없으면 200.
            boolean prepared = orderColumn(orderId, "attempt_requested_at") != null;
            return expect(decline(bearer, eventId, orderId), prepared ? 409 : 200, prepared ? "포기 거절" : "포기");
        }
        if (pick == 1) {
            return "아무것도 안 함";
        }
        if (pick == 2) {
            // 승인을 시작한 뒤 PG 승인이 기한을 1초 넘겨 도착했다. 결제 모듈이 걸러 주는 경우라 Handler를 직접 부른다.
            Long attempt = startApproval(orderId);
            Instant late = deadline(orderId).plusSeconds(1);
            inTx(() -> handler().onApproved(orderId, new ApprovedPayment(attempt, "pg-" + attempt, "pk-" + attempt,
                    fee, late.minusSeconds(1), late)));
            return "늦은 승인";
        }
        Prepared prepared = prepare(bearer, orderId, "sweep-" + key + "-" + orderId);
        assertThat(prepared.status()).as(prepared.body()).isLessThan(300);
        switch (pick) {
            case 3, 4 -> {
                fakePg.willApprove(prepared.pgOrderId());
                confirm(bearer, prepared);
                return "승인";
            }
            case 5 -> {
                fakePg.willReject(prepared.pgOrderId(), "REJECT_CARD_COMPANY");
                confirm(bearer, prepared);
                return "카드 거절";
            }
            case 6 -> {
                fakePg.willTimeout(prepared.pgOrderId());
                confirm(bearer, prepared);
                return "결과 모름(PG 기록 없음)";
            }
            case 7 -> {
                fakePg.willLoseResponse(prepared.pgOrderId());
                confirm(bearer, prepared);
                return "결과 모름(응답 유실)";
            }
            default -> {
                return "결제창만 열어 둠";
            }
        }
    }

    private static String expect(MvcResult result, int status, String action) {
        assertThat(result.getResponse().getStatus()).as("%s: %s", action, contentOf(result)).isEqualTo(status);
        return action;
    }

    /** 결제 대기이고 진행 중인 결제 시도가 없는 주문. */
    private List<Long> payableOrderIds(Long eventId) {
        return jdbc.queryForList("""
                SELECT order_id FROM fan_meeting_orders
                 WHERE event_id = ? AND status = 'PENDING_PAYMENT' AND payment_id IS NULL ORDER BY order_id
                """, Long.class, eventId);
    }

    // ── 도우미: 모두 API·읽기만 쓴다 ───────────────────────────────────────────────────────────────

    /** 첫날에서 days일 뒤의 그 시각 (Asia/Seoul). */
    static Instant at(int days, int hour, int minute) {
        return DAY.plusDays(days).atTime(hour, minute).atZone(SEOUL).toInstant();
    }

    /** 주최자 API로 행사를 만든다. 마감은 오픈 10분 뒤로 정해진다. */
    Long createEvent(int winnerLimit, int reserveLimit, long feeAmount, Instant opensAt, Instant meetingAt,
                     Instant promotionClosesAt) throws Exception {
        String body = """
                {"title":"관통 팬미팅","entryOpensAt":"%s","firstNoticeScheduledAt":"%s",
                 "winnerLimit":%d,"reserveLimit":%d,"feeAmount":%d,
                 "meetingAt":"%s","promotionClosesAt":"%s","opensAtPublic":true}
                """.formatted(opensAt, opensAt, winnerLimit, reserveLimit, feeAmount, meetingAt, promotionClosesAt);
        String res = mockMvc.perform(post("/api/organizer/fan-meetings")
                        .header(HttpHeaders.AUTHORIZATION, token("ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.eventId")).longValue();
    }

    org.springframework.test.web.servlet.ResultActions result(String bearer, Long eventId) throws Exception {
        return mockMvc.perform(get("/api/fan-meetings/{eventId}/result/me", eventId)
                .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /** 주문 주인의 로그인 토큰. 지금 시계로 새로 발급한다. */
    String bearerOfOrder(Long orderId) {
        return bearerOf(((Number) orderColumn(orderId, "user_id")).longValue());
    }

    /** 예비 순번이 승격으로 받은 주문. */
    Long promotedOrderId(Long eventId, int reserveRank) {
        for (Map<String, Object> order : promotedOrders(eventId)) {
            if (((Number) order.get("selection_rank")).intValue() == reserveRank) {
                return ((Number) order.get("order_id")).longValue();
            }
        }
        throw new AssertionError("예비 " + reserveRank + "번의 주문이 없습니다");
    }

    void check(List<Long> eventIds, String when) {
        FanMeetingInvariants.assertHolds(jdbc, eventIds, when);
    }

    static int occurrences(CapturedOutput output, String text) {
        String all = output.getAll();
        int count = 0;
        for (int i = all.indexOf(text); i >= 0; i = all.indexOf(text, i + text.length())) {
            count++;
        }
        return count;
    }
}
