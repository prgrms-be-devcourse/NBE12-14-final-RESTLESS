package com.restless.fanmeeting.application;

import com.restless.fanmeeting.domain.FanMeetingDrawRanking;
import com.restless.fanmeeting.domain.FanMeetingDrawRun;
import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingOrder;
import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingApplicationRepository.Candidate;
import com.restless.fanmeeting.infrastructure.FanMeetingDrawRunRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingEventRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingOrderRepository;
import com.restless.fanmeeting.infrastructure.FanMeetingSelectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추첨 (transactions.md 5절): ① Claim(트랜잭션) → ② 계산(잠금 없음) → ③ 최종화(트랜잭션).
 * 잠금 순서는 Event FOR UPDATE → DrawRun FOR UPDATE.
 */
@Service
@RequiredArgsConstructor
public class FanMeetingDrawTransitions {

    /** 탈퇴 여부를 한 번에 묻는 회원 수. */
    static final int MEMBER_LOOKUP_CHUNK = 1_000;

    private static final Logger log = LoggerFactory.getLogger(FanMeetingDrawTransitions.class);

    private final FanMeetingEventRepository events;
    private final FanMeetingDrawRunRepository drawRuns;
    private final FanMeetingApplicationRepository applications;
    private final FanMeetingSelectionRepository selections;
    private final FanMeetingOrderRepository orders;
    private final MemberPort memberPort;
    private final Clock clock;

    /** Claim에 성공한 Worker가 들고 다니는 값. seed와 token은 응답·로그에 내보내지 않는다. */
    public record DrawClaim(Long drawRunId, Long eventId, String leaseToken, byte[] seed, int winnerLimit,
                            int reserveLimit, long feeAmount) {
    }

    /** 계산 결과. 순번 순서대로 담는다. candidateCount는 탈퇴 회원을 뺀 후보 수. */
    public record DrawPlan(List<Candidate> winners, List<Candidate> reserves, int candidateCount) {
    }

    @Transactional(readOnly = true)
    public List<Long> findDue(int limit) {
        return drawRuns.findIdsToDraw(clock.instant(), PageRequest.of(0, limit));
    }

    /** ① Claim. 가져갈 수 없으면(이미 완료, 다른 Worker가 lease 보유) 빈 값. */
    @Transactional(timeout = 5)
    public Optional<DrawClaim> claim(Long drawRunId) {
        Long eventId = drawRuns.findEventIdById(drawRunId).orElse(null);
        if (eventId == null) {
            return Optional.empty();
        }
        FanMeetingEvent event = events.findByIdForUpdate(eventId).orElseThrow();
        FanMeetingDrawRun run = drawRuns.findByIdForUpdate(drawRunId).orElseThrow();
        Instant now = clock.instant();
        if (!run.isClaimable(now)) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        run.claim(token, now);
        event.startDrawing(now);
        return Optional.of(new DrawClaim(run.getId(), eventId, token, run.getRandomSeed().clone(),
                event.getWinnerLimit(), event.getReserveLimit(), event.getFeeAmount()));
    }

    /** ② 계산. 잠그지 않는다. 탈퇴 회원은 빼고(응모 행은 그대로), 순위 키로 당첨·예비를 정한다. */
    @Transactional(readOnly = true)
    public DrawPlan compute(DrawClaim claim) {
        List<Candidate> applied = applications.findCandidates(claim.eventId());
        Set<Long> active = activeUserIds(applied);
        List<Candidate> candidates = applied.stream().filter(c -> active.contains(c.getUserId())).toList();
        Map<Long, Candidate> byApplicationId = candidates.stream()
                .collect(Collectors.toMap(Candidate::getApplicationId, Function.identity()));

        FanMeetingDrawRanking.Result ranked = FanMeetingDrawRanking.rank(claim.seed(),
                candidates.stream().map(Candidate::getApplicationId).toList(), claim.winnerLimit(),
                claim.reserveLimit());
        return new DrawPlan(ranked.winners().stream().map(byApplicationId::get).toList(),
                ranked.reserves().stream().map(byApplicationId::get).toList(), candidates.size());
    }

    /**
     * ③ 최종화. 이미 완료됐거나(멱등) lease가 다른 Worker에게 넘어갔으면 아무것도 저장하지 않고 빈 목록.
     * 완료 확인을 token 확인보다 먼저 한다. 반대면 완료된 추첨에 같은 token의 재시도가 선발을 또 넣으려다 UNIQUE에 걸린다.
     * 저장 중 오류는 전체 롤백된다. 돌려준 알림 요청은 커밋 뒤에 보낸다.
     */
    @Transactional(timeout = 30)
    public List<NotificationRequest> finalizeDraw(DrawClaim claim, DrawPlan plan) {
        FanMeetingEvent event = events.findByIdForUpdate(claim.eventId()).orElseThrow();
        FanMeetingDrawRun run = drawRuns.findByIdForUpdate(claim.drawRunId()).orElseThrow();
        if (run.isCompleted()) {
            log.info("FANMEETING_DRAW_ALREADY_FINALIZED drawRunId={}", run.getId());
            return List.of();
        }
        if (!run.isHeldBy(claim.leaseToken())) {
            log.warn("FANMEETING_DRAW_LEASE_LOST drawRunId={}", run.getId());
            return List.of();
        }
        Instant now = clock.instant();
        List<NotificationRequest> notifications = new ArrayList<>();
        int rank = 1;
        for (Candidate winner : plan.winners()) {
            FanMeetingSelection selection = selections.save(
                    FanMeetingSelection.winner(run, winner.getApplicationId(), winner.getUserId(), rank++, now));
            FanMeetingOrder order = orders.save(FanMeetingOrder.issue(selection, claim.feeAmount(), now));
            notifications.add(NotificationRequest.winner(event.getId(), order.getId(), order.paymentDeadline()));
        }
        rank = 1;
        for (Candidate reserve : plan.reserves()) {
            selections.save(
                    FanMeetingSelection.reserve(run, reserve.getApplicationId(), reserve.getUserId(), rank++, now));
        }
        run.complete(plan.candidateCount(), now);
        event.finishDraw(plan.candidateCount() > 0, now);
        return notifications;
    }

    private Set<Long> activeUserIds(List<Candidate> applied) {
        List<Long> userIds = applied.stream().map(Candidate::getUserId).toList();
        Set<Long> active = new HashSet<>();
        for (int from = 0; from < userIds.size(); from += MEMBER_LOOKUP_CHUNK) {
            active.addAll(memberPort.activeMemberIds(
                    userIds.subList(from, Math.min(from + MEMBER_LOOKUP_CHUNK, userIds.size()))));
        }
        return active;
    }
}
