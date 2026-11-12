package com.restless.payment.infrastructure.toss;

import com.restless.payment.application.pg.PgApproveCommand;
import com.restless.payment.application.pg.PgApprovalResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TossPaymentsTimeoutTest {
    @Test void INV10_configuredReadTimeoutReturnsUnknownWhileLocalServerIsStillWaiting() throws Exception {
        // Local HTTP fixture only. No external PG calls.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/payments/confirm", exchange -> {
            received.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var properties = new TossPaymentsProperties("test_sk_dummy", "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(1), Duration.ofMillis(100));
            var client = new TossPaymentsClient(new TossPaymentsConfig().tossRestClient(properties), properties);
            var future = executor.submit(() -> client.approve(new PgApproveCommand("payment", "order-123", 1000, "key")));
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            // Without applying readTimeout, the request would still wait for the server latch.
            assertThat(future.get(2, TimeUnit.SECONDS)).isInstanceOf(PgApprovalResult.Unknown.class);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
