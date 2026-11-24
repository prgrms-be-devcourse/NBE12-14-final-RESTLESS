package com.restless.drop.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class DropQueueConcurrencyWebConfig implements WebMvcConfigurer {
    private final DropQueueConcurrencyInterceptor interceptor;

    public DropQueueConcurrencyWebConfig(DropQueueConcurrencyInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 사용자별 요청 횟수 제한(기본 순서 0)을 먼저 통과한 요청만 허용치를 쓴다.
        registry.addInterceptor(interceptor).order(100)
                .addPathPatterns("/api/drops/*/queue/entries", "/api/drops/*/queue/me");
    }
}
