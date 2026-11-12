package com.restless.auth.infrastructure;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증 규칙.
 *
 * <ul>
 *   <li>기본: 모든 요청은 로그인 필요.</li>
 *   <li>공개: 가입, 로그인, health, 아래 {@link #PUBLIC_GET_PATHS}의 조회.</li>
 *   <li>{@code /api/admin/**}: ADMIN만.</li>
 * </ul>
 *
 * <p>로그인 없이 볼 조회 API를 만들면 {@link #PUBLIC_GET_PATHS}에 추가한다.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, RefreshTokenProperties.class})
public class SecurityConfig implements WebMvcConfigurer {

    /** 로그인 없이 GET으로 볼 수 있는 경로. 팀 API 계약(docs/contracts/api) 기준. */
    static final String[] PUBLIC_GET_PATHS = {
            "/api/concerts", "/api/concerts/**",
            "/api/concert-sessions/*/seats",
            "/api/drops", "/api/drops/*",
            "/api/auctions", "/api/auctions/*", "/api/auctions/*/bids", "/api/auctions/*/result",
            "/api/fan-meetings/*",
            "/actuator/health", "/actuator/health/**",
    };

    /** 토큰 없이 부르는 인증 API. refresh, logout은 Access Token이 만료된 뒤에도 불려야 한다. */
    static final String[] AUTH_PUBLIC_POST_PATHS = {
            "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper, Clock clock)
            throws Exception {
        JsonSecurityErrorHandler errorHandler = new JsonSecurityErrorHandler(jsonMapper, clock);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AUTH_PUBLIC_POST_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtTokenProvider tokenProvider) {
        return tokenProvider.decoder();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginMemberArgumentResolver());
    }

    /**
     * refresh, logout은 만료된 Access Token이 헤더에 함께 와도 무시한다.
     * 그렇지 않으면 공개 경로여도 만료 토큰 때문에 401이 나서 재발급을 못 한다.
     */
    private BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String uri = request.getRequestURI();
            if (uri.equals("/api/auth/refresh") || uri.equals("/api/auth/logout")) {
                return null;
            }
            return delegate.resolve(request);
        };
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtTokenProvider.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
