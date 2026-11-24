package com.restless.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.auth.infrastructure.SecurityConfig;
import com.restless.common.error.GlobalExceptionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** 몰리는 쓰기 API 4개의 회원별 횟수 제한. 한도는 실제 application.yml 값을 쓴다. */
class WriteRateLimitApiTest {

    private static final String APPLY = "/api/fan-meetings/1/applications";
    private static final String SEAT = "/api/ticket-reservations";
    private static final String BID = "/api/auctions/1/bids";
    private static final String DROP = "/api/drops/1/reservations";
    private static final int LIMIT = 10;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {APPLY, SEAT, BID, DROP})
    void 한_회원은_1초에_10번까지_통과하고_11번째는_컨트롤러_전에_429(String path) throws Exception {
        start(Map.of());

        pass(path, "1", LIMIT);

        post(path, "1").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        assertThat(context.getBean(Endpoints.class).calls.get()).isEqualTo(LIMIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {APPLY, SEAT, BID, DROP})
    void 창이_끝나기_1ms_전에는_막고_끝나는_시각부터_허용한다(String path) throws Exception {
        start(Map.of());
        pass(path, "1", LIMIT);

        advance(999);
        post(path, "1").andExpect(status().isTooManyRequests());
        advance(1);
        pass(path, "1", LIMIT);
        post(path, "1").andExpect(status().isTooManyRequests());
    }

    @Test
    void 막힌_동안_더_눌러도_기다리는_시간이_늘지_않는다() throws Exception {
        start(Map.of());
        pass(SEAT, "1", LIMIT);
        for (int i = 0; i < 9; i++) {
            advance(100);
            post(SEAT, "1").andExpect(status().isTooManyRequests());
        }

        advance(100);

        post(SEAT, "1").andExpect(status().isOk());
    }

    @Test
    void 네_API는_따로_센다() throws Exception {
        start(Map.of());

        for (String path : new String[]{APPLY, SEAT, BID, DROP}) pass(path, "1", LIMIT);
    }

    @ParameterizedTest
    @CsvSource({"/api/fan-meetings/1/applications,/api/fan-meetings/2/applications",
            "/api/auctions/1/bids,/api/auctions/2/bids", "/api/drops/1/reservations,/api/drops/2/reservations"})
    void 대상이_달라도_같은_회원은_합쳐_세고_다른_회원은_따로_센다(String first, String second) throws Exception {
        start(Map.of());
        pass(first, "1", LIMIT);

        post(second, "1").andExpect(status().isTooManyRequests());
        post(first, "2").andExpect(status().isOk());
    }

    @Test
    void 같은_IP의_여러_회원은_서로_영향을_주지_않는다() throws Exception {
        start(Map.of());

        for (int user = 1; user <= 30; user++) post(APPLY, String.valueOf(user)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/auctions/1/bids", "GET,/api/fan-meetings/1/applications", "DELETE,/api/ticket-reservations",
            "DELETE,/api/fan-meetings/1/applications/me", "POST,/api/drops/1/reservations/1/cancel",
            "POST,/api/ticket-reservations/1/checkout", "DELETE,/api/ticket-reservations/1"})
    void 조회와_취소와_결제는_제한하지_않는다(String method, String path) throws Exception {
        start(Map.of());

        for (int i = 0; i < LIMIT + 2; i++) call(method, path, "1").andExpect(status().isOk());
    }

    @Test
    void 토큰이_없거나_회원_번호가_아니면_전처럼_401() throws Exception {
        start(Map.of());

        post(SEAT, null).andExpect(status().isUnauthorized());
        post(SEAT, "abc").andExpect(status().isUnauthorized());
        assertThat(context.getBean(Endpoints.class).calls.get()).isZero();
    }

    @Test
    void 제한을_끄면_막지_않는다() throws Exception {
        start(Map.of("restless.ratelimit.enabled", "false"));

        pass(BID, "1", LIMIT + 2);
    }

    @Test
    void 거절_지표는_규칙별로_쌓이고_회원_번호가_없다() throws Exception {
        start(Map.of());
        pass(BID, "1", LIMIT);
        post(BID, "1").andExpect(status().isTooManyRequests());

        MeterRegistry meters = context.getBean(MeterRegistry.class);
        assertThat(meters.get("restless.ratelimit.rejected").tag("rule", "auction_bid").counter().count())
                .isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
    }

    @Test
    void 쓰기_한도는_일부만_적으면_기동하지_않는다() {
        var rule = new RateLimitProperties.Rule(10, java.time.Duration.ofSeconds(1));

        assertThatThrownBy(() -> new RateLimitProperties.Write(null, rule, rule, rule))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Write(rule, null, rule, rule))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Write(rule, rule, null, rule))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Write(rule, rule, rule, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fan-meeting-application", "ticket-reservation", "auction-bid", "drop-reservation"})
    void 보관_시간이_쓰기_창보다_짧으면_기동하지_않는다(String name) {
        assertThatThrownBy(() -> start(Map.of("restless.ratelimit.write." + name + ".window", "3m")))
                .hasStackTraceContaining("expiration must cover every window");
    }

    @Test
    void 설정_파일은_네_한도와_저장_크기_10만을_정한다() throws Exception {
        start(Map.of());

        RateLimitProperties properties = context.getBean(RateLimitProperties.class);
        var expected = new RateLimitProperties.Rule(LIMIT, java.time.Duration.ofSeconds(1));
        assertThat(properties.write())
                .isEqualTo(new RateLimitProperties.Write(expected, expected, expected, expected));
        assertThat(properties.maximumSize()).isEqualTo(100_000);
    }

    private void start(Map<String, Object> overrides) throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();
        Map<String, Object> values = new HashMap<>();
        values.put("restless.auth.jwt.secret", "test-secret-at-least-thirty-two-bytes-long");
        values.put("restless.ratelimit.enabled", "true");
        values.putAll(overrides);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        context.getEnvironment().getPropertySources().addLast(yaml);
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private void advance(long millis) {
        context.getBean(RateLimitApiTest.MutableClock.class).advanceMillis(millis);
    }

    private void pass(String path, String user, int times) throws Exception {
        for (int i = 0; i < times; i++) post(path, user).andExpect(status().isOk());
    }

    private ResultActions post(String path, String user) throws Exception {
        return call("POST", path, user);
    }

    private ResultActions call(String method, String path, String user) throws Exception {
        var builder = request(HttpMethod.valueOf(method), path);
        if (user != null) builder.with(jwt().jwt(token -> token.subject(user)));
        return mvc.perform(builder);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ComponentScan(basePackages = "com.restless.common.ratelimit",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*RateLimitApiTest.*"))
    @Import({SecurityConfig.class, JwtTokenProvider.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean RateLimitApiTest.MutableClock clock() { return new RateLimitApiTest.MutableClock(); }
        @Bean MeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    @TestComponent
    static class Endpoints {
        final AtomicInteger calls = new AtomicInteger();

        @RequestMapping({"/api/fan-meetings/{eventId}/applications", "/api/fan-meetings/{eventId}/applications/me",
                "/api/ticket-reservations", "/api/ticket-reservations/{reservationId}",
                "/api/ticket-reservations/{reservationId}/checkout", "/api/auctions/{auctionId}/bids",
                "/api/drops/{dropId}/reservations", "/api/drops/{dropId}/reservations/{reservationId}/cancel"})
        Map<String, Object> respond() { return Map.of("calls", calls.incrementAndGet()); }
    }
}
