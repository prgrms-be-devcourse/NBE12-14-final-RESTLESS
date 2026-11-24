package com.restless.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.restless.member.application.MemberService;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 탈퇴 회원 쓰기 차단 스위치. 끄는 값(false)을 명시했을 때만 필터를 등록하지 않는다. */
class WithdrawnMemberBlockSwitchTest {

    private static final String SWITCH = "restless.auth.withdrawn-block.enabled";
    private static final String CUSTOMIZER = "withdrawnMemberFilterCustomizer";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MemberService.class, () -> mock(MemberService.class))
            .withBean("handlerExceptionResolver", HandlerExceptionResolver.class,
                    () -> mock(HandlerExceptionResolver.class))
            .withUserConfiguration(WithdrawnMemberSecurityConfig.class);

    @Test
    void 설정이_없으면_켜진다() {
        runner.run(context -> assertThat(context).hasBean(CUSTOMIZER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "", "fales"})
    void false가_아니면_켜진다(String value) {
        runner.withPropertyValues(SWITCH + "=" + value).run(context -> assertThat(context).hasBean(CUSTOMIZER));
    }

    @Test
    void false면_꺼진다() {
        runner.withPropertyValues(SWITCH + "=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CUSTOMIZER));
    }

    @Test
    void 기본_설정과_prod_설정에서는_켜진다() {
        runner.withInitializer(context -> load(context, "application.yml", "application-prod.yml"))
                .run(context -> assertThat(context).hasBean(CUSTOMIZER));
    }

    @Test
    void perf_설정에서는_꺼진다() {
        runner.withInitializer(context -> load(context, "application.yml", "application-perf.yml"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CUSTOMIZER));
    }

    @Test
    void perf_설정에서도_환경변수로_켤_수_있다() {
        runner.withPropertyValues("WITHDRAWN_BLOCK_ENABLED=true")
                .withInitializer(context -> load(context, "application.yml", "application-perf.yml"))
                .run(context -> assertThat(context).hasBean(CUSTOMIZER));
    }

    /** 뒤에 적은 파일이 앞선다. 프로필 설정이 기본 설정을 덮는 것과 같다. */
    private static void load(ConfigurableApplicationContext context, String... files) {
        for (String file : files) {
            try {
                new YamlPropertySourceLoader().load(file, new ClassPathResource(file))
                        .forEach(source -> context.getEnvironment().getPropertySources().addFirst(source));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
