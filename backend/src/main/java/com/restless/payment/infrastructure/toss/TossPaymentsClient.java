package com.restless.payment.infrastructure.toss;

import com.restless.payment.application.pg.PgApprovalResult;
import com.restless.payment.application.pg.PgApproveCommand;
import com.restless.payment.application.pg.PgCancelCommand;
import com.restless.payment.application.pg.PgCancelResult;
import com.restless.payment.application.pg.PgClient;
import com.restless.payment.application.pg.PgQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.function.Function;

@Component
@ConditionalOnProperty(name = "pg.provider", havingValue = "toss", matchIfMissing = true)
public class TossPaymentsClient implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(TossPaymentsClient.class);
    private final RestClient restClient;
    private final TossPaymentsProperties properties;

    public TossPaymentsClient(@Qualifier("tossRestClient") RestClient restClient, TossPaymentsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public PgApprovalResult approve(PgApproveCommand command) {
        var request = restClient.post().uri("/v1/payments/confirm")
            .headers(h -> h.setBasicAuth(properties.secretKey(), "", StandardCharsets.UTF_8))
            .header("Idempotency-Key", command.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ConfirmRequest(command.paymentKey(), command.pgOrderId(), command.amount()));
        return call("approve", command.pgOrderId(), request, p -> {
            if (!command.pgOrderId().equals(p.orderId()) || !command.paymentKey().equals(p.paymentKey())) {
                throw new IllegalArgumentException("Mismatched payment identity");
            }
            return TossResponseClassifier.approvalSuccess(p);
        }, TossResponseClassifier::approvalError, reason -> new PgApprovalResult.Unknown(reason, null));
    }

    @Override
    public PgQueryResult query(String pgOrderId) {
        var request = restClient.get().uri("/v1/payments/orders/{orderId}", pgOrderId)
            .headers(h -> h.setBasicAuth(properties.secretKey(), "", StandardCharsets.UTF_8));
        return call("query", pgOrderId, request, p -> {
            if (!pgOrderId.equals(p.orderId())) throw new IllegalArgumentException("Mismatched order identity");
            return TossResponseClassifier.querySuccess(p);
        }, (status, code, message) -> TossResponseClassifier.queryError(status, code), PgQueryResult.Unknown::new);
    }

    @Override
    public PgCancelResult cancel(PgCancelCommand command) {
        var request = restClient.post().uri("/v1/payments/{paymentKey}/cancel", command.paymentKey())
            .headers(h -> h.setBasicAuth(properties.secretKey(), "", StandardCharsets.UTF_8))
            .header("Idempotency-Key", command.idempotencyKey())
            .contentType(MediaType.APPLICATION_JSON).body(new CancelRequest(command.reason()));
        return call("cancel", "unavailable", request, p -> {
            if (!command.paymentKey().equals(p.paymentKey())) throw new IllegalArgumentException("Mismatched payment identity");
            return TossResponseClassifier.cancelSuccess(p);
        }, TossResponseClassifier::cancelError, reason -> new PgCancelResult.Unknown(reason, null));
    }

    private <T> T call(String operation, String orderId, RestClient.RequestHeadersSpec<?> request,
                       Function<TossPaymentResponse, T> success, ErrorClassifier<T> error, Function<String, T> unknown) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                T result;
                String code = null;
                String loggedOrderId = orderId;
                if (status == 200) {
                    TossPaymentResponse payment = response.bodyTo(TossPaymentResponse.class);
                    if (payment == null) throw new IllegalArgumentException("Empty payment response");
                    result = success.apply(payment);
                    if (payment.orderId() != null) loggedOrderId = payment.orderId();
                    if (payment.failure() != null) code = payment.failure().code();
                } else {
                    TossErrorResponse failure = response.bodyTo(TossErrorResponse.class);
                    code = failure == null ? null : failure.code();
                    result = error.classify(status, code, failure == null ? null : failure.message());
                }
                logResult(operation, loggedOrderId, status, code, result);
                return result;
            });
        } catch (RestClientException | IllegalArgumentException | DateTimeException exception) {
            T result = unknown.apply("PG transport or response failure");
            // Never log exception messages, raw responses, credentials or Authorization headers.
            logResult(operation, orderId, 0, null, result);
            return result;
        }
    }

    private static void logResult(String operation, String orderId, int status, String code, Object result) {
        String classification = result.getClass().getSimpleName();
        if (result instanceof PgApprovalResult.Unknown || result instanceof PgCancelResult.Unknown
            || result instanceof PgQueryResult.Unknown) {
            log.warn("PG operation={} result={} pgOrderId={} httpStatus={} code={}", operation, classification, orderId, status, code);
        } else {
            log.info("PG operation={} result={} pgOrderId={} httpStatus={} code={}", operation, classification, orderId, status, code);
        }
        if (TossResponseClassifier.isConfigurationError(status, code)) {
            log.error("PG configuration error operation={} pgOrderId={} httpStatus={} code={}", operation, orderId, status, code);
        }
    }

    private record ConfirmRequest(String paymentKey, String orderId, long amount) { }
    private record CancelRequest(String cancelReason) { }
    @FunctionalInterface
    private interface ErrorClassifier<T> { T classify(int status, String code, String message); }
}
