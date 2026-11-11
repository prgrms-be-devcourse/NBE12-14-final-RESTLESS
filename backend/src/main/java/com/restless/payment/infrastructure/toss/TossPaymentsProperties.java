package com.restless.payment.infrastructure.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("toss")
public record TossPaymentsProperties(String secretKey, String baseUrl, Duration connectTimeout, Duration readTimeout) {
    public TossPaymentsProperties {
        secretKey = secretKey == null ? "" : secretKey;
        baseUrl = baseUrl == null ? "https://api.tosspayments.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("PG timeouts must be positive");
        }
    }

    @Override
    public String toString() {
        return "TossPaymentsProperties[secretKey=<redacted>, baseUrl=" + baseUrl
            + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
