package com.restless.payment.application;

import com.restless.payment.application.pg.*;
import com.restless.payment.domain.*;
import com.restless.payment.infrastructure.PaymentRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Queries may overlap across servers. Only locked state transitions invoke domain callbacks. */
@Service
@EnableConfigurationProperties(PaymentReconciliationProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentReconciler {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private final PaymentRepository payments;
    private final PaymentTransactions transactions;
    private final PgClient pg;
    private final Clock clock;
    private final MeterRegistry meters;
    private final PaymentReconciliationProperties properties;
    private volatile UnknownStats unknown = new UnknownStats(0, null);

    public PaymentReconciler(
            PaymentRepository payments,
            PaymentTransactions transactions,
            PgClient pg,
            Clock clock,
            MeterRegistry meters,
            PaymentReconciliationProperties properties) {
        this.payments = payments;
        this.transactions = transactions;
        this.pg = pg;
        this.clock = clock;
        this.meters = meters;
        this.properties = properties;
        Gauge.builder("payment.unknown.count", this, r -> r.unknown.count()).register(meters);
        Gauge.builder(
                        "payment.unknown.oldest.age.seconds",
                        this,
                        PaymentReconciler::unknownOldestAgeSeconds)
                .register(meters);
    }

    private double unknownOldestAgeSeconds() {
        UnknownStats snapshot = unknown;
        return snapshot.oldest() == null
                ? 0
                : Math.max(0, Duration.between(snapshot.oldest(), clock.instant()).toSeconds());
    }

    public void runOnce() {
        Timer.Sample timer = Timer.start(meters);
        try {
            Instant now = clock.instant();
            // Materialize all groups first: a PROCESSING -> UNKNOWN change is not queried twice in
            // one run.
            var processing =
                    candidates(
                            PaymentStatus.PROCESSING, now.minus(properties.processingStaleAfter()));
            var unknowns =
                    candidates(PaymentStatus.UNKNOWN, now.minus(properties.unknownRetryAfter()));
            var ready =
                    select(
                            "ready_expired",
                            () ->
                                    payments.findByStatusAndExpiresAtBeforeOrderByExpiresAtAscIdAsc(
                                            PaymentStatus.READY,
                                            now,
                                            PageRequest.of(0, properties.batchSize())));
            var cancelling =
                    candidates(
                            PaymentStatus.CANCEL_PENDING, now.minus(properties.cancelStaleAfter()));
            var cancelUnknown =
                    candidates(
                            PaymentStatus.CANCEL_UNKNOWN,
                            now.minus(properties.unknownRetryAfter()));
            for (var p : processing) approval(p, "processing");
            for (var p : unknowns) approval(p, "unknown");
            for (var p : ready) {
                try {
                    processed(
                            "ready_expired",
                            transactions.expireReady(p.id()) ? "expired" : "skipped");
                } catch (RuntimeException error) {
                    failure("apply", p.id(), error);
                }
            }
            for (var p : cancelling) cancellation(p);
            for (var p : cancelUnknown) cancellation(p);
            try {
                var summary = payments.summarizeUnknown();
                unknown = new UnknownStats(summary.getCount(), summary.getOldest());
            } catch (RuntimeException error) {
                failure("monitor", null, error);
            }
        } finally {
            timer.stop(meters.timer("payment.reconcile.run"));
        }
    }

    private List<Candidate> candidates(PaymentStatus status, Instant cutoff) {
        return select(
                status.name().toLowerCase(Locale.ROOT),
                () ->
                        payments.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAscIdAsc(
                                status, cutoff, PageRequest.of(0, properties.batchSize())));
    }

    private List<Candidate> select(String target, Supplier<List<Payment>> query) {
        try {
            return query.get().stream()
                    .map(p -> new Candidate(p.getId(), p.getPgOrderId(), p.getStatus()))
                    .toList();
        } catch (RuntimeException error) {
            failure("select_" + target, null, error);
            return List.of();
        }
    }

    private void approval(Candidate p, String target) {
        PgQueryResult result;
        try {
            result = pg.query(p.orderId());
        } catch (RuntimeException error) {
            failure("query", p.id(), error);
            result = new PgQueryResult.Unknown("PG query failed");
        }
        try {
            var step =
                    transactions.reconcileApproval(
                            p.id(), p.status(), result, properties.notFoundFinalAfter());
            processed(target, step.status().name().toLowerCase(Locale.ROOT));
            if (step.compensation() != null) {
                // Use the persisted reason (bounded to the provider limit), including on the
                // first cancellation, so every request with this key has the same payload.
                var cancellation =
                        transactions.cancellation(step.paymentId(), PaymentStatus.CANCEL_PENDING);
                if (cancellation != null) cancel(cancellation, PaymentStatus.CANCEL_PENDING, false);
            }
        } catch (RuntimeException error) {
            failure("apply", p.id(), error);
        }
    }

    private void cancellation(Candidate p) {
        PaymentTransactions.Cancellation c;
        try {
            c = transactions.cancellation(p.id(), p.status());
        } catch (RuntimeException error) {
            failure("cancel_prepare", p.id(), error);
            return;
        }
        if (c == null) {
            processed("cancel", "skipped");
            return;
        }
        PgQueryResult result;
        try {
            result = pg.query(p.orderId());
        } catch (RuntimeException error) {
            failure("query", p.id(), error);
            result = new PgQueryResult.Unknown("PG query failed");
        }
        try {
            if (result instanceof PgQueryResult.Cancelled cancelled) {
                processed(
                        "cancel",
                        transactions
                                .applyCancellation(
                                        c.paymentId(),
                                        c.refundId(),
                                        p.status(),
                                        new PgCancelResult.Cancelled(
                                                cancelled.transactionKey(),
                                                cancelled.cancelledAt()))
                                .toLowerCase(Locale.ROOT));
            } else if (result instanceof PgQueryResult.Approved) {
                // Recheck under locks after query before sending the same idempotent cancellation.
                c = transactions.cancellation(p.id(), p.status());
                if (c != null) cancel(c, p.status(), true);
                else processed("cancel", "skipped");
            } else {
                transactions.recordCancelCheck(p.id(), p.status());
                processed("cancel", "unresolved");
            }
        } catch (RuntimeException error) {
            failure("cancel_apply", p.id(), error);
        }
    }

    private void cancel(PaymentTransactions.Cancellation c, PaymentStatus expected, boolean retry) {
        if (retry) {
            // No per-refund tag: an id tag creates one time series per refund. The log keeps the id.
            meters.counter("payment.reconcile.cancel.retry").increment();
            log.info(
                    "Retrying PG cancellation paymentId={} refundId={}",
                    c.paymentId(),
                    c.refundId());
        }
        PgCancelResult result;
        try {
            result = pg.cancel(c.command());
        } catch (RuntimeException error) {
            failure("cancel", c.paymentId(), error);
            result = new PgCancelResult.Unknown("PG cancellation failed", null);
        }
        try {
            processed(
                    "cancel",
                    transactions
                            .applyCancellation(c.paymentId(), c.refundId(), expected, result)
                            .toLowerCase(Locale.ROOT));
        } catch (RuntimeException error) {
            failure("cancel_apply", c.paymentId(), error);
        }
    }

    private void processed(String target, String result) {
        meters.counter("payment.reconcile.processed", "target", target, "result", result)
                .increment();
    }

    private void failure(String stage, Long paymentId, RuntimeException error) {
        // Payloads and exception messages can contain paymentKey or credentials.
        log.error(
                "Payment reconciliation failed stage={} paymentId={} errorType={} stack={}",
                stage,
                paymentId,
                error.getClass().getName(),
                Arrays.toString(error.getStackTrace()));
        meters.counter("payment.reconcile.failure", "stage", stage).increment();
    }

    private record Candidate(Long id, String orderId, PaymentStatus status) {}

    private record UnknownStats(long count, Instant oldest) {}
}
