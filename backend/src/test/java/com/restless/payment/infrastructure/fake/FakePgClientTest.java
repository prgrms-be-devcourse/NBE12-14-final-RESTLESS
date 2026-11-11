package com.restless.payment.infrastructure.fake;

import com.restless.payment.application.pg.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class FakePgClientTest {
    static final Instant NOW = Instant.parse("2026-11-12T01:00:00Z");
    FakePgClient client = new FakePgClient(new FakePgProperties(null, 0, 0, 0), new Random(42), Clock.fixed(NOW, ZoneOffset.UTC));
    PgApproveCommand command(String order, String key) {
        return new PgApproveCommand("payment-" + order, order, 1000, key);
    }

    @Test void defaultsToApprovalAndQueryAndCancelAreConsistent() {
        assertThat(client.approve(command("order-1", "approve-key"))).isEqualTo(
            new PgApprovalResult.Approved("payment-order-1", "order-1", 1000, NOW));
        assertThat(client.query("order-1")).isEqualTo(
            new PgQueryResult.Approved("payment-order-1", "order-1", 1000, NOW));
        PgCancelResult result = client.cancel(new PgCancelCommand("payment-order-1", "reason", "cancel-key"));
        assertThat(result).isInstanceOf(PgCancelResult.Cancelled.class);
        var cancelled = (PgCancelResult.Cancelled) result;
        assertThat(cancelled.transactionKey()).isNotBlank();
        assertThat(cancelled.cancelledAt()).isEqualTo(NOW);
        assertThat(client.query("order-1")).isEqualTo(
            new PgQueryResult.Cancelled(cancelled.transactionKey(), NOW));
        assertThat(client.cancel(new PgCancelCommand("payment-order-1", "reason", "cancel-key"))).isSameAs(result);
    }

    @Test void scenariosOverrideGlobalDefaultsAndResetClearsStateAndIdempotency() {
        client.willReject("order-1", "REJECT_ACCOUNT_PAYMENT");
        assertThat(client.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Rejected.class);
        assertThat(client.query("order-1")).isEqualTo(
            new PgQueryResult.NotApproved("ABORTED", "REJECT_ACCOUNT_PAYMENT", "Fake PG rejection"));
        client.reset();
        assertThat(client.query("order-1")).isEqualTo(new PgQueryResult.NotFound());
        client.willApprove("order-1");
        assertThat(client.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Approved.class);
    }

    @Test void INV10_timeoutDoesNotRecordPgApproval() {
        client.willTimeout("order-1");
        PgApprovalResult first = client.approve(command("order-1", "key"));
        assertThat(first).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(client.query("order-1")).isEqualTo(new PgQueryResult.NotFound());
        client.willApprove("order-1");
        assertThat(client.approve(command("order-1", "key"))).isSameAs(first);
        assertThat(client.approve(command("order-1", "different-key"))).isInstanceOf(PgApprovalResult.Approved.class);
    }

    @Test void INV10_lostResponseIsUnknownButQueryFindsApprovedPayment() {
        client.willLoseResponse("order-1");
        assertThat(client.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(client.query("order-1")).isEqualTo(
            new PgQueryResult.Approved("payment-order-1", "order-1", 1000, NOW));
    }

    @Test void idempotentReplayReturnsFirstResponseEvenAfterCancellation() {
        var request = command("order-1", "key");
        var first = client.approve(request);
        assertThat(client.approve(request)).isSameAs(first);
        client.cancel(new PgCancelCommand("payment-order-1", "reason", "cancel-key"));
        assertThat(client.approve(request)).isSameAs(first);
    }

    @Test void INV10_differentKeyDuplicateApprovalIsUnknownAlreadyProcessed() {
        client.approve(command("order-1", "key"));
        assertThat(client.approve(command("order-1", "other-key"))).isEqualTo(
            new PgApprovalResult.Unknown("Payment already processed", "ALREADY_PROCESSED_PAYMENT"));
    }

    @Test void concurrentTwentyApprovalsProduceExactlyOneApprovalRecord() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<PgApprovalResult>>();
            for (int i = 0; i < 20; i++) {
                String key = "key-" + i;
                futures.add(pool.submit(() -> { start.await(); return client.approve(command("order-1", key)); }));
            }
            start.countDown();
            int approved = 0;
            int unknown = 0;
            for (var future : futures) {
                var result = future.get(10, TimeUnit.SECONDS);
                if (result instanceof PgApprovalResult.Approved) approved++;
                if (result instanceof PgApprovalResult.Unknown) unknown++;
            }
            assertThat(approved).isEqualTo(1);
            assertThat(unknown).isEqualTo(19);
            assertThat(client.query("order-1")).isInstanceOf(PgQueryResult.Approved.class);
        }
    }

    @Test void concurrentSameIdempotencyKeyReturnsIdenticalFirstResponse() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<PgApprovalResult>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> client.approve(command("order-1", "same")));
            var responses = pool.invokeAll(tasks);
            Object first = responses.getFirst().get();
            assertThat(first).isInstanceOf(PgApprovalResult.Approved.class);
            for (var response : responses) assertThat(response.get()).isSameAs(first);
        }
    }

    @Test void globalRatesCanForceRejectionOrUnknownAndScenariosOverrideThem() {
        var reject = new FakePgClient(new FakePgProperties(null, 1, 0, 0), new Random(42), Clock.systemUTC());
        assertThat(reject.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Rejected.class);
        reject.willApprove("order-2");
        assertThat(reject.approve(command("order-2", "key-2"))).isInstanceOf(PgApprovalResult.Approved.class);
        var unknown = new FakePgClient(new FakePgProperties(null, 0, 1, 0), new Random(42), Clock.systemUTC());
        assertThat(unknown.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(unknown.query("order-1")).isEqualTo(new PgQueryResult.NotFound());
    }

    @Test void seededRandomProducesRepeatableOutcomes() {
        var properties = new FakePgProperties(null, 0.3, 0.3, 0);
        var first = new FakePgClient(properties, new Random(12), Clock.fixed(NOW, ZoneOffset.UTC));
        var second = new FakePgClient(properties, new Random(12), Clock.fixed(NOW, ZoneOffset.UTC));
        for (int i = 0; i < 30; i++) {
            var command = command("order-" + i, "key-" + i);
            assertThat(first.approve(command)).isEqualTo(second.approve(command));
        }
    }

    @Test void perOrderDelayActuallyDelaysApproval() {
        client.willDelay("order-1", Duration.ofMillis(30));
        long start = System.nanoTime();
        assertThat(client.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Approved.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(30));
    }

    @Test void INV10_globalLostResponseRateApprovesAtPgButAnswersUnknown() {
        var lost = new FakePgClient(new FakePgProperties(null, 0, 0, 1), new Random(42), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(lost.approve(command("order-1", "key"))).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(lost.query("order-1")).isInstanceOf(PgQueryResult.Approved.class);
    }

    @Test void slowApprovalDoesNotBlockOtherOrders() throws Exception {
        client.willDelay("slow", Duration.ofSeconds(1));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<PgApprovalResult> slow = pool.submit(() -> client.approve(command("slow", "slow-key")));
            Thread.sleep(50);
            long start = System.nanoTime();
            for (int i = 0; i < 64; i++) {
                assertThat(client.approve(command("fast-" + i, "fast-key-" + i))).isInstanceOf(PgApprovalResult.Approved.class);
            }
            // Fast approvals must not wait behind the slow one (latency is simulated outside the map locks).
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(slow.isDone()).isFalse();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isInstanceOf(PgApprovalResult.Approved.class);
        }
    }

    @Test void invalidRatesOrDelayFailFast() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FakePgProperties(null, 0.8, 0.8, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FakePgProperties(null, -0.1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FakePgProperties(null, Double.NaN, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FakePgProperties(Duration.ofMillis(-1), 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FakePgProperties(null, 0.5, 0.3, 0.3));
    }
}
