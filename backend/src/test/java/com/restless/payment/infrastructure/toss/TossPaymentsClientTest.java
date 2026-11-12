package com.restless.payment.infrastructure.toss;

import com.restless.payment.application.pg.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class TossPaymentsClientTest {
    static final String BASE = "https://pg.invalid";
    static final PgApproveCommand COMMAND = new PgApproveCommand("payment-key", "order-123", 1000, "approve-idem");
    static final String APPROVED = """
        {"paymentKey":"payment-key","orderId":"order-123","status":"DONE","totalAmount":1200,
         "approvedAt":"2024-02-13T12:18:14+09:00","extraFutureField":true}
        """;
    static final String CANCELLED = """
        {"paymentKey":"payment-key","orderId":"order-123","status":"CANCELED","cancels":[
          {"transactionKey":"cancel-tx","canceledAt":"2024-02-14T12:18:14+09:00","cancelStatus":"DONE"}]}
        """;
    MockRestServiceServer server;
    TossPaymentsClient client;

    @BeforeEach void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder.build(),
            new TossPaymentsProperties("test_sk_dummy", BASE, null, null));
    }

    @Test void approvalSendsBasicAuthIdempotencyAndBodyAndReturnsActualPgAmount() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString("test_sk_dummy:".getBytes(StandardCharsets.UTF_8))))
            .andExpect(header("Idempotency-Key", "approve-idem"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"paymentKey\":\"payment-key\",\"orderId\":\"order-123\",\"amount\":1000}"))
            .andRespond(withSuccess(APPROVED, MediaType.APPLICATION_JSON));
        assertThat(client.approve(COMMAND)).isEqualTo(new PgApprovalResult.Approved(
            "payment-key", "order-123", 1200, Instant.parse("2024-02-13T03:18:14Z")));
        server.verify();
    }

    @Test void queryUsesOrderUrlAndParsesApproval() {
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic dGVzdF9za19kdW1teTo="))
            .andRespond(withSuccess(APPROVED, MediaType.APPLICATION_JSON));
        assertThat(client.query("order-123")).isEqualTo(new PgQueryResult.Approved(
            "payment-key", "order-123", 1200, Instant.parse("2024-02-13T03:18:14Z")));
        server.verify();
    }

    @Test void cancelSendsIdempotencyAndReasonAndParsesCancellationMetadata() {
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic dGVzdF9za19kdW1teTo="))
            .andExpect(header("Idempotency-Key", "cancel-idem"))
            .andExpect(content().json("{\"cancelReason\":\"customer request\"}"))
            .andRespond(withSuccess(CANCELLED, MediaType.APPLICATION_JSON));
        assertThat(client.cancel(new PgCancelCommand("payment-key", "customer request", "cancel-idem")))
            .isEqualTo(new PgCancelResult.Cancelled("cancel-tx", Instant.parse("2024-02-14T03:18:14Z")));
        server.verify();
    }

    @Test void INV10_httpApprovalErrorsAreClassifiedConservatively() {
        server.expect(requestTo(BASE + "/v1/payments/confirm")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"declined\"}"));
        server.expect(requestTo(BASE + "/v1/payments/confirm")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"error\"}"));
        assertThat(client.approve(COMMAND)).isEqualTo(new PgApprovalResult.Rejected("REJECT_CARD_COMPANY", "declined"));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "null", "", "{\"status\":\"DONE\",\"totalAmount\":100}",
        "{\"paymentKey\":\"key\",\"orderId\":\"order-123\",\"status\":\"DONE\",\"totalAmount\":100,\"approvedAt\":\"bad-time\"}"})
    void INV10_malformedOrIncompleteApprovalResponseIsUnknown(String body) {
        server.expect(requestTo(BASE + "/v1/payments/confirm")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        server.verify();
    }

    @Test void INV10_timeoutAndIoFailureBecomeUnknownForEveryOperation() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
            .andRespond(withException(new SocketTimeoutException("dummy timeout")));
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andRespond(withException(new IOException("dummy connection failure")));
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel"))
            .andRespond(withException(new IOException("dummy connection failure")));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(client.query("order-123")).isInstanceOf(PgQueryResult.Unknown.class);
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isInstanceOf(PgCancelResult.Unknown.class);
        server.verify();
    }

    @Test void INV10_queryNotFoundAndCancelledAreDistinct() {
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123")).andRespond(withStatus(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"missing\"}"));
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andRespond(withSuccess(CANCELLED, MediaType.APPLICATION_JSON));
        assertThat(client.query("order-123")).isEqualTo(new PgQueryResult.NotFound());
        assertThat(client.query("order-123")).isEqualTo(new PgQueryResult.Cancelled(
            "cancel-tx", Instant.parse("2024-02-14T03:18:14Z")));
        server.verify();
    }

    @Test void INV10_cancelErrorsDoNotTreatRefundProcessingFailureAsRejection() {
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"FAILED_REFUND_PROCESS\",\"message\":\"unknown\"}"));
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isInstanceOf(PgCancelResult.Unknown.class);
        server.verify();
    }

    @Test void alreadyCancelledLooksUpPaymentToRecoverCancelTransactionKey() {
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"done\"}"));
        server.expect(requestTo(BASE + "/v1/payments/payment-key")).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic "
                + Base64.getEncoder().encodeToString("test_sk_dummy:".getBytes(StandardCharsets.UTF_8))))
            .andRespond(withSuccess(CANCELLED, MediaType.APPLICATION_JSON));
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isEqualTo(new PgCancelResult.Cancelled("cancel-tx", Instant.parse("2024-02-14T03:18:14Z")));
        server.verify();
    }

    @Test void INV10_alreadyCancelledStaysUnknownWhenLookupFails() {
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"ALREADY_CANCELED_PAYMENT\",\"message\":\"done\"}"));
        server.expect(requestTo(BASE + "/v1/payments/payment-key"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"retry\"}"));
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isEqualTo(new PgCancelResult.Unknown("HTTP 500", "ALREADY_CANCELED_PAYMENT"));
        server.verify();
    }

    @Test void transportFailureLogsExceptionTypeWithoutMessage(CapturedOutput output) {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
            .andRespond(withException(new SocketTimeoutException("secret-ish detail")));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(output.getAll()).contains("failureType=ResourceAccessException/SocketTimeoutException")
            .doesNotContain("secret-ish detail");
        server.verify();
    }


    @Test void INV10_configurationErrorsAreUnknownAndLoggedWithoutCredentials(CapturedOutput output) {
        server.expect(requestTo(BASE + "/v1/payments/confirm")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"UNAUTHORIZED_KEY\",\"message\":\"test_sk_dummy\"}"));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        assertThat(output.getAll()).contains("WARN", "ERROR", "UNAUTHORIZED_KEY", "order-123")
            .doesNotContain("test_sk_dummy", "dGVzdF9za19kdW1teTo=", "Authorization");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "null"})
    void INV10_queryAndCancelParsingFailuresAreUnknown(String body) {
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.query("order-123")).isInstanceOf(PgQueryResult.Unknown.class);
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isInstanceOf(PgCancelResult.Unknown.class);
        server.verify();
    }

    @Test void INV10_partialCancellationDoesNotConfirmFullRefund() {
        String partial = CANCELLED.replace("CANCELED", "PARTIAL_CANCELED");
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andRespond(withSuccess(partial, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/payment-key/cancel"))
            .andRespond(withSuccess(partial, MediaType.APPLICATION_JSON));
        assertThat(client.query("order-123")).isInstanceOf(PgQueryResult.Unknown.class);
        assertThat(client.cancel(new PgCancelCommand("payment-key", "reason", "key")))
            .isInstanceOf(PgCancelResult.Unknown.class);
        server.verify();
    }

    @Test void INV10_identityMismatchCannotApproveAnotherOrder() {
        server.expect(requestTo(BASE + "/v1/payments/confirm"))
            .andRespond(withSuccess(APPROVED.replace("order-123", "order-other"), MediaType.APPLICATION_JSON));
        assertThat(client.approve(COMMAND)).isInstanceOf(PgApprovalResult.Unknown.class);
        server.verify();
    }

    @Test void INV10_queryFailureStatusUsesProviderFailureFields() {
        server.expect(requestTo(BASE + "/v1/payments/orders/order-123"))
            .andRespond(withSuccess("""
                {"orderId":"order-123","status":"ABORTED","failure":{"code":"REJECT_CARD_COMPANY","message":"declined"}}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.query("order-123")).isEqualTo(
            new PgQueryResult.NotApproved("ABORTED", "REJECT_CARD_COMPANY", "declined"));
        server.verify();
    }

    @Test void configurationDefaultsAndSecretRedaction() {
        TossPaymentsProperties p = new TossPaymentsProperties("test_sk_dummy", null, null, null);
        assertThat(p.baseUrl()).isEqualTo("https://api.tosspayments.com");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.toString()).doesNotContain("test_sk_dummy");
    }
}
