package com.restless.payment.infrastructure.fake;

import com.restless.payment.application.pg.PgApprovalResult;
import com.restless.payment.application.pg.PgApproveCommand;
import com.restless.payment.application.pg.PgCancelCommand;
import com.restless.payment.application.pg.PgCancelResult;
import com.restless.payment.application.pg.PgClient;
import com.restless.payment.application.pg.PgQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

@Component
@Profile("!prod")
@ConditionalOnProperty(name = "pg.provider", havingValue = "fake")
@EnableConfigurationProperties(FakePgProperties.class)
public class FakePgClient implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(FakePgClient.class);
    private final FakePgProperties properties;
    private final RandomGenerator random;
    private final Clock clock;
    private final ConcurrentHashMap<String, PgQueryResult> orders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> paymentOrders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PgApprovalResult> approvals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PgCancelResult> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Scenario> scenarios = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Duration> delays = new ConcurrentHashMap<>();

    @Autowired
    public FakePgClient(FakePgProperties properties, ObjectProvider<RandomGenerator> random) {
        this(properties, random.getIfAvailable(RandomGenerator::getDefault), Clock.systemUTC());
    }

    public FakePgClient(FakePgProperties properties, RandomGenerator random, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.random = Objects.requireNonNull(random);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PgApprovalResult approve(PgApproveCommand command) {
        PgApprovalResult result = approvals.get(command.idempotencyKey());
        if (result == null) {
            // Simulated latency runs outside the map locks: sleeping inside computeIfAbsent would also block
            // unrelated requests whose keys share a hash bin and distort latency experiments.
            try {
                Thread.sleep(delays.getOrDefault(command.pgOrderId(), properties.approveDelay()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new PgApprovalResult.Unknown("Fake PG interrupted", null);
            }
            result = approvals.computeIfAbsent(command.idempotencyKey(), key -> approveOnce(command));
        }
        logResult("approve", command.pgOrderId(), result);
        return result;
    }

    private PgApprovalResult approveOnce(PgApproveCommand command) {
        AtomicReference<PgApprovalResult> result = new AtomicReference<>();
        // The per-order compute is the linearization point, including requests with different idempotency keys.
        orders.compute(command.pgOrderId(), (orderId, existing) -> {
            if (existing != null) {
                result.set(new PgApprovalResult.Unknown("Payment already processed", "ALREADY_PROCESSED_PAYMENT"));
                return existing;
            }
            Scenario scenario = scenarios.get(orderId);
            if (scenario == null) scenario = randomScenario();
            if (scenario.mode() == Mode.TIMEOUT) {
                result.set(new PgApprovalResult.Unknown("Fake PG timeout", null));
                return null;
            }
            if (scenario.mode() == Mode.REJECT) {
                result.set(new PgApprovalResult.Rejected(scenario.code(), "Fake PG rejection"));
                return new PgQueryResult.NotApproved("ABORTED", scenario.code(), "Fake PG rejection");
            }
            String otherOrder = paymentOrders.putIfAbsent(command.paymentKey(), orderId);
            if (otherOrder != null && !otherOrder.equals(orderId)) {
                result.set(new PgApprovalResult.Unknown("Payment already processed", "ALREADY_PROCESSED_PAYMENT"));
                return null;
            }
            var approved = new PgApprovalResult.Approved(command.paymentKey(), orderId, command.amount(), clock.instant());
            result.set(scenario.mode() == Mode.LOSE_RESPONSE
                ? new PgApprovalResult.Unknown("Fake PG response lost", null) : approved);
            return new PgQueryResult.Approved(approved.paymentKey(), orderId, approved.approvedAmount(), approved.approvedAt());
        });
        return result.get();
    }

    @Override
    public PgQueryResult query(String pgOrderId) {
        PgQueryResult result = orders.getOrDefault(pgOrderId, new PgQueryResult.NotFound());
        logResult("query", pgOrderId, result);
        return result;
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        PgCancelResult result = cancellations.computeIfAbsent(command.idempotencyKey(), key -> cancelOnce(command));
        logResult("cancel", paymentOrders.getOrDefault(command.paymentKey(), "unavailable"), result);
        return result;
    }

    private PgCancelResult cancelOnce(PgCancelCommand command) {
        String orderId = paymentOrders.get(command.paymentKey());
        if (orderId == null) return new PgCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "No approved payment");
        AtomicReference<PgCancelResult> result = new AtomicReference<>();
        orders.compute(orderId, (id, existing) -> {
            if (existing instanceof PgQueryResult.Cancelled cancelled) {
                result.set(new PgCancelResult.Cancelled(cancelled.transactionKey(), cancelled.cancelledAt()));
                return existing;
            }
            if (existing instanceof PgQueryResult.Approved) {
                var cancelled = new PgCancelResult.Cancelled("FAKE-" + UUID.randomUUID(), clock.instant());
                result.set(cancelled);
                return new PgQueryResult.Cancelled(cancelled.transactionKey(), cancelled.cancelledAt());
            }
            result.set(new PgCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "No approved payment"));
            return existing;
        });
        return result.get();
    }

    private Scenario randomScenario() {
        double sample;
        synchronized (random) { sample = random.nextDouble(); }
        if (sample < properties.rejectRate()) return new Scenario(Mode.REJECT, "REJECT_CARD_COMPANY");
        if (sample < properties.rejectRate() + properties.unknownRate()) return new Scenario(Mode.TIMEOUT, null);
        if (sample < properties.rejectRate() + properties.unknownRate() + properties.lostResponseRate()) {
            return new Scenario(Mode.LOSE_RESPONSE, null);
        }
        return new Scenario(Mode.APPROVE, null);
    }

    public void willApprove(String pgOrderId) { scenarios.put(pgOrderId, new Scenario(Mode.APPROVE, null)); }
    public void willReject(String pgOrderId, String code) {
        scenarios.put(pgOrderId, new Scenario(Mode.REJECT, Objects.requireNonNull(code)));
    }
    public void willTimeout(String pgOrderId) { scenarios.put(pgOrderId, new Scenario(Mode.TIMEOUT, null)); }
    public void willLoseResponse(String pgOrderId) { scenarios.put(pgOrderId, new Scenario(Mode.LOSE_RESPONSE, null)); }
    public void willDelay(String pgOrderId, Duration delay) {
        if (delay.isNegative()) throw new IllegalArgumentException("Fake PG delay must be nonnegative");
        delays.put(pgOrderId, delay);
    }

    /** Reset between test/load-test runs, after all in-flight requests have finished. */
    public void reset() {
        orders.clear();
        paymentOrders.clear();
        approvals.clear();
        cancellations.clear();
        scenarios.clear();
        delays.clear();
    }

    private static void logResult(String operation, String orderId, Object result) {
        String code = switch (result) {
            case PgApprovalResult.Rejected r -> r.code();
            case PgApprovalResult.Unknown r -> r.code();
            case PgCancelResult.Rejected r -> r.code();
            case PgCancelResult.Unknown r -> r.code();
            case PgQueryResult.NotApproved r -> r.code();
            default -> null;
        };
        if (result instanceof PgApprovalResult.Unknown || result instanceof PgCancelResult.Unknown
            || result instanceof PgQueryResult.Unknown) {
            log.warn("Fake PG operation={} result={} pgOrderId={} code={}", operation, result.getClass().getSimpleName(), orderId, code);
        } else {
            log.info("Fake PG operation={} result={} pgOrderId={} code={}", operation, result.getClass().getSimpleName(), orderId, code);
        }
    }

    private enum Mode { APPROVE, REJECT, TIMEOUT, LOSE_RESPONSE }
    private record Scenario(Mode mode, String code) { }
}
