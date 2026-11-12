package com.restless.payment.infrastructure.toss;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TossPaymentsProperties.class)
@ConditionalOnProperty(name = "pg.provider", havingValue = "toss", matchIfMissing = true)
public class TossPaymentsConfig {
    @Bean
    RestClient tossRestClient(TossPaymentsProperties properties) {
        // Without a key every call returns 401 and each payment silently piles up as UNKNOWN. Fail at startup instead.
        if (properties.secretKey().isBlank()) {
            throw new IllegalStateException("toss.secret-key (TOSS_SECRET_KEY) is required when pg.provider=toss");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }
}
