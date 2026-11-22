package com.restless.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.auth.infrastructure.SecurityConfig;
import com.restless.common.error.GlobalExceptionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

class RateLimitApiTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    private void start(Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>(Map.ofEntries(
                Map.entry("restless.auth.jwt.secret", "test-secret-at-least-thirty-two-bytes-long"),
                Map.entry("restless.ratelimit.enabled", "true"),
                Map.entry("restless.ratelimit.maximum-size", "100"),
                Map.entry("restless.ratelimit.expire-after-access", "2m"),
                Map.entry("restless.ratelimit.login.limit", "2"),
                Map.entry("restless.ratelimit.login.window", "1m"),
                Map.entry("restless.ratelimit.signup.limit", "2"),
                Map.entry("restless.ratelimit.signup.window", "1m"),
                Map.entry("restless.ratelimit.refresh.limit", "2"),
                Map.entry("restless.ratelimit.refresh.window", "1m"),
                Map.entry("restless.ratelimit.queue-entry.limit", "2"),
                Map.entry("restless.ratelimit.queue-entry.window", "10s"),
                Map.entry("restless.ratelimit.queue-status.limit", "2"),
                Map.entry("restless.ratelimit.queue-status.window", "1s")));
        values.putAll(overrides);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authLimitsReturnCommonJsonAndRetryAfterBeforeController() throws Exception {
        start(Map.of());
        for (String endpoint : new String[]{"login", "signup", "refresh"}) {
            String path = "/api/auth/" + endpoint;
            call(HttpMethod.POST, path, null, "10.0.0.1").andExpect(status().isOk());
            call(HttpMethod.POST, path, null, "10.0.0.1").andExpect(status().isOk());
            call(HttpMethod.POST, path, null, "10.0.0.1")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(header().string("Retry-After", "60"));
            call(HttpMethod.POST, path, null, "10.0.0.2").andExpect(status().isOk());
        }
        assertThat(context.getBean(Endpoints.class).calls.get()).isEqualTo(9);
        assertThat(context.getBean(MeterRegistry.class).get("restless.ratelimit.rejected")
                .tag("rule", "login").counter().count()).isEqualTo(1);
    }

    @Test
    void queueLimitsArePerUserAcrossDropsAndIps() throws Exception {
        start(Map.of());
        for (String suffix : new String[]{"entries", "me"}) {
            HttpMethod method = suffix.equals("me") ? HttpMethod.GET : HttpMethod.POST;
            call(method, "/api/drops/1/queue/" + suffix, "1", "10.0.0.1").andExpect(status().isOk());
            call(method, "/api/drops/2/queue/" + suffix, "1", "10.0.0.2").andExpect(status().isOk());
            call(method, "/api/drops/3/queue/" + suffix, "1", "10.0.0.3")
                    .andExpect(status().isTooManyRequests());
            call(method, "/api/drops/1/queue/" + suffix, "2", "10.0.0.1").andExpect(status().isOk());
        }
        assertThat(context.getBean(Endpoints.class).calls.get()).isEqualTo(6);
    }

    @Test
    void fixedWindowResetsAtBoundaryAndReappliesLimitWithRoundedRetry() throws Exception {
        start(Map.of("restless.ratelimit.login.limit", "1"));
        call(HttpMethod.POST, "/api/auth/login", null, "ip").andExpect(status().isOk());
        context.getBean(MutableClock.class).advanceMillis(59_001);
        call(HttpMethod.POST, "/api/auth/login", null, "ip")
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        context.getBean(MutableClock.class).advanceMillis(999);
        call(HttpMethod.POST, "/api/auth/login", null, "ip").andExpect(status().isOk());
        call(HttpMethod.POST, "/api/auth/login", null, "ip")
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void headRequestsShareGetStatusLimitThroughGetMapping() throws Exception {
        start(Map.of("restless.ratelimit.queue-status.limit", "1"));
        String path = "/api/drops/1/queue/me";
        call(HttpMethod.HEAD, path, "1", "ip").andExpect(status().isOk());
        call(HttpMethod.HEAD, path, "1", "ip")
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        call(HttpMethod.GET, path, "1", "ip")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        call(HttpMethod.GET, path, "2", "ip").andExpect(status().isOk());
        call(HttpMethod.HEAD, path, "2", "ip").andExpect(status().isTooManyRequests());
        assertThat(context.getBean(Endpoints.class).calls.get()).isEqualTo(2);
    }

    @Test
    void otherMethodsDoNotInvokeGetMappingOrConsumeStatusLimit() throws Exception {
        start(Map.of("restless.ratelimit.queue-status.limit", "1"));
        String path = "/api/drops/1/queue/me";
        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            call(method, path, "1", "ip").andExpect(status().isMethodNotAllowed());
        }
        call(HttpMethod.OPTIONS, path, "1", "ip").andExpect(status().isOk());
        call(HttpMethod.TRACE, path, "1", "ip").andExpect(status().is4xxClientError());
        assertThat(context.getBean(Endpoints.class).calls.get()).isZero();
        call(HttpMethod.GET, path, "1", "ip").andExpect(status().isOk());
        assertThat(context.getBean(Endpoints.class).calls.get()).isEqualTo(1);
    }

    @Test
    void unrelatedPathsAndMethodsAreUnlimited() throws Exception {
        start(Map.of());
        for (int i = 0; i < 12; i++) {
            for (String path : new String[]{"/api/payments", "/api/drops/1/reservations",
                    "/api/drops/1/reservations/1/cancel", "/api/auth/logout", "/api/auth/login/extra"}) {
                call(HttpMethod.POST, path, "1", "ip").andExpect(status().isOk());
            }
            call(HttpMethod.GET, "/api/auth/login", "1", "ip").andExpect(status().isOk());
            call(HttpMethod.GET, "/api/drops/1", null, "ip").andExpect(status().isOk());
        }
    }

    @Test
    void disabledPropertyBypassesLimits() throws Exception {
        start(Map.of("restless.ratelimit.enabled", "false"));
        for (int i = 0; i < 12; i++) {
            call(HttpMethod.POST, "/api/auth/login", null, "ip").andExpect(status().isOk());
            call(HttpMethod.GET, "/api/drops/1/queue/me", "1", "ip").andExpect(status().isOk());
        }
    }

    @Test
    void forwardedHeaderCannotChangeIpBucket() throws Exception {
        start(Map.of("restless.ratelimit.login.limit", "1"));
        mvc.perform(request(HttpMethod.POST, "/api/auth/login").header("X-Forwarded-For", "1.1.1.1"))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.POST, "/api/auth/login").header("X-Forwarded-For", "2.2.2.2"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void missingOrNonNumericJwtSubjectKeepsUnauthorizedResponse() throws Exception {
        start(Map.of());
        call(HttpMethod.GET, "/api/drops/1/queue/me", null, "ip").andExpect(status().isUnauthorized());
        call(HttpMethod.GET, "/api/drops/1/queue/me", "invalid", "ip")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void applicationYamlDefinesEnabledDefaultsAndAllFiveLimits() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).getFirst();
        Map<String, Object> defaults = new HashMap<>();
        for (String name : new String[]{"enabled", "maximum-size", "expire-after-access",
                "login.limit", "login.window", "signup.limit", "signup.window", "refresh.limit", "refresh.window",
                "queue-entry.limit", "queue-entry.window", "queue-status.limit", "queue-status.window"}) {
            String key = "restless.ratelimit." + name;
            defaults.put(key, source.getProperty(key));
        }
        assertThat(defaults.get("restless.ratelimit.enabled")).isEqualTo(true);
        start(defaults);
        String[] paths = {"/api/auth/login", "/api/auth/signup", "/api/auth/refresh",
                "/api/drops/1/queue/entries", "/api/drops/1/queue/me"};
        int[] limits = {20, 10, 30, 5, 5};
        for (int route = 0; route < paths.length; route++) {
            HttpMethod method = route == 4 ? HttpMethod.GET : HttpMethod.POST;
            String user = route >= 3 ? "1" : null;
            for (int i = 0; i < limits[route]; i++) {
                call(method, paths[route], user, "ip").andExpect(status().isOk());
            }
            call(method, paths[route], user, "ip").andExpect(status().isTooManyRequests());
        }
        for (int i = 0; i < 40; i++) {
            context.getBean(MutableClock.class).advanceMillis(i % 2 == 0 ? 1000 : 3000);
            call(HttpMethod.GET, "/api/drops/1/queue/me", "1", "ip").andExpect(status().isOk());
        }
    }

    @Test
    void contextPathStillUsesSameRateLimit() throws Exception {
        start(Map.of("restless.ratelimit.login.limit", "1"));
        mvc.perform(request(HttpMethod.POST, "/app/api/auth/login").contextPath("/app"))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.POST, "/app/api/auth/login").contextPath("/app"))
                .andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions call(HttpMethod method, String path,
                                                                    String user, String ip) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).with(req -> {
            req.setRemoteAddr(ip);
            return req;
        });
        if (user != null) builder.with(jwt().jwt(token -> token.subject(user)));
        return mvc.perform(builder);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ComponentScan(basePackages = "com.restless.common.ratelimit",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*RateLimitApiTest.*"))
    @Import({SecurityConfig.class, JwtTokenProvider.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean MeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class Endpoints {
        final AtomicInteger calls = new AtomicInteger();
        @RequestMapping({"/api/auth/login", "/api/auth/signup", "/api/auth/refresh", "/api/auth/logout",
                "/api/drops/{dropId}/queue/entries",
                "/api/payments", "/api/drops/{dropId}/reservations",
                "/api/drops/{dropId}/reservations/{reservationId}/cancel", "/api/auth/login/extra",
                "/api/drops/{dropId}"})
        Map<String, Object> respond() { return Map.of("calls", calls.incrementAndGet()); }

        @GetMapping("/api/drops/{dropId}/queue/me")
        Map<String, Object> queueStatus() { return Map.of("calls", calls.incrementAndGet()); }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T00:00:00Z");
        void advanceMillis(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
