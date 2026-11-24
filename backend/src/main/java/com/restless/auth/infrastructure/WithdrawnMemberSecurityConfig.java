package com.restless.auth.infrastructure;

import com.restless.member.application.MemberService;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@link WithdrawnMemberFilter}를 인가 필터 바로 뒤에 넣는다. Spring Security가 HttpSecurity를 만들 때 적용한다.
 *
 * <p>값이 false일 때만 끈다. 설정이 없거나 값이 잘못되면 켜진다. perf 프로필은 기본으로 끈다.
 */
@Configuration
@ConditionalOnProperty(name = "restless.auth.withdrawn-block.enabled", matchIfMissing = true)
class WithdrawnMemberSecurityConfig {

    @Bean
    Customizer<HttpSecurity> withdrawnMemberFilterCustomizer(MemberService memberService,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher publicPaths = new OrRequestMatcher(Arrays.stream(SecurityConfig.AUTH_PUBLIC_POST_PATHS)
                .<RequestMatcher>map(path -> paths.matcher(HttpMethod.POST, path))
                .toList());
        return http -> http.addFilterAfter(
                new WithdrawnMemberFilter(memberService, exceptionResolver, publicPaths), AuthorizationFilter.class);
    }
}
