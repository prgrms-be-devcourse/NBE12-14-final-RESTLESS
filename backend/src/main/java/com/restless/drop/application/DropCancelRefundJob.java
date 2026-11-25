package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.drop.domain.DropReservationStatus;
import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.payment.application.PaymentRefundService;
import com.restless.payment.application.RefundPaymentCommand;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 삭제된 드롭의 결제 완료 건 환불 (#430). 매일 00:30(KST)에 시작하고 마감 없이 대상이 없을 때까지 돈다 (#435).
 * 기준 시각은 그날 00:30(KST)이다. 그 뒤에 삭제된 드롭은 다음 날 00:30에 처리한다. 서버가 여러 대여도 기준이 같다.
 * 회원이 직접 요청한 환불은 이 작업과 상관없이 즉시 처리된다.
 *
 * <p>손님 환불과 같은 {@link PaymentRefundService} 경로를 쓴다. 멱등 키는 점유마다 고정이라
 * 배치가 다시 돌거나 서버 두 대가 같이 돌아도 PG 취소는 한 번만 나간다. 결과를 모르는 환불은
 * 미완료 결제 재확인 작업이 마무리한다. 이 작업은 트랜잭션을 열지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DropCancelRefundJob {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final LocalTime RUN_AT = LocalTime.of(0, 30);
    static final String REASON = "드롭 삭제에 따른 환불";
    private static final int PAGE = 100;
    private static final Logger log = LoggerFactory.getLogger(DropCancelRefundJob.class);

    private final DropReservationRepository reservations;
    private final PaymentRefundService refunds;
    private final Clock clock;
    private final MeterRegistry meters;

    public void runOnce() {
        run(cutoff(clock.instant()));
    }

    /** {@code now} 이전의 가장 최근 00:30(KST). 00:30 전에 손으로 돌리면 전날 00:30이다. */
    static Instant cutoff(Instant now) {
        var local = now.atZone(ZONE);
        var today = local.toLocalDate().atTime(RUN_AT).atZone(ZONE);
        return (local.isBefore(today) ? today.minusDays(1) : today).toInstant();
    }

    /**
     * @param cutoff 이 시각 전에 삭제된 드롭만 처리한다
     * @return 환불을 요청한 점유 수
     */
    public int run(Instant cutoff) {
        int requested = 0;
        long after = 0;
        while (true) {
            java.util.List<Long> page;
            try {
                page = reservations.findCancelRefundCandidates(cutoff, after, PageRequest.of(0, PAGE));
            } catch (RuntimeException failure) {
                failure(null, failure);
                return requested;
            }
            if (page.isEmpty()) return requested;
            for (Long id : page) {
                if (refund(id)) requested++;
                after = id;
            }
        }
    }

    private boolean refund(Long reservationId) {
        try {
            var reservation = reservations.findById(reservationId).orElse(null);
            if (reservation == null || reservation.getStatus() != DropReservationStatus.CONFIRMED
                    || reservation.getCurrentPaymentId() == null) {
                count("skipped");
                return false;
            }
            var result = refunds.refund(new RefundPaymentCommand(reservation.getUserId(),
                    reservation.getCurrentPaymentId(), "drop-cancel-" + reservationId, REASON));
            count(switch (result.status()) {
                case COMPLETED -> "completed";
                case FAILED -> "rejected";
                case REQUESTED, UNKNOWN -> "unknown";
            });
            return true;
        } catch (BusinessException rejected) {
            // PG final rejection (REFUND_REVIEW), a refund already in progress, or a state the payment cannot refund.
            count(switch (rejected.getErrorCode()) {
                case PAYMENT_REFUND_REJECTED -> "rejected";
                case PAYMENT_REFUND_IN_PROGRESS -> "in_progress";
                default -> "not_refundable";
            });
            log.warn("Drop cancel refund not done reservationId={} code={}", reservationId, rejected.getErrorCode());
            return false;
        } catch (RuntimeException failure) {
            failure(reservationId, failure);
            return false;
        }
    }

    private void count(String result) {
        meters.counter("drop.cancel_refund.processed", "result", result).increment();
    }

    private void failure(Long reservationId, RuntimeException failure) {
        log.error("Drop cancel refund failed reservationId={} errorType={}", reservationId, failure.getClass().getName());
        count("failed");
    }
}
