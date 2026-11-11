package com.restless.payment.infrastructure;

import com.restless.payment.application.pg.PgClient;
import com.restless.payment.infrastructure.fake.FakePgClient;
import com.restless.payment.infrastructure.fake.FakePgProperties;
import com.restless.payment.infrastructure.toss.TossPaymentsClient;
import com.restless.payment.infrastructure.toss.TossPaymentsConfig;
import com.restless.payment.infrastructure.toss.TossPaymentsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PgClientConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TossPaymentsConfig.class, TossPaymentsClient.class, FakePgClient.class);

    @Test void fakeProviderCreatesOnlyFakeClientWithoutDatabase() {
        runner.withPropertyValues("pg.provider=fake").run(context -> {
            assertThat(context).hasSingleBean(PgClient.class).hasSingleBean(FakePgClient.class)
                .doesNotHaveBean(TossPaymentsClient.class);
            assertThat(context.getBean(FakePgProperties.class).approveDelay()).isEqualTo(Duration.ZERO);
        });
    }

    @Test void unspecifiedProviderDefaultsToTossWithoutDatabase() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PgClient.class).hasSingleBean(TossPaymentsClient.class)
                .doesNotHaveBean(FakePgClient.class);
            assertThat(context.getBean(TossPaymentsProperties.class).readTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test void prodAndFakeCannotCreateFakeEvenWhenDevIsAlsoActive() {
        runner.withPropertyValues("spring.profiles.active=prod,dev", "pg.provider=fake")
            .run(context -> assertThat(context).doesNotHaveBean(FakePgClient.class).doesNotHaveBean(PgClient.class));
    }

    @Test void prodCanUseTossAndTimeoutsBindFromProperties() {
        runner.withPropertyValues("spring.profiles.active=prod", "pg.provider=toss", "toss.connect-timeout=1s", "toss.read-timeout=2s")
            .run(context -> {
                assertThat(context).hasSingleBean(TossPaymentsClient.class).doesNotHaveBean(FakePgClient.class);
                assertThat(context.getBean(TossPaymentsProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                assertThat(context.getBean(TossPaymentsProperties.class).readTimeout()).isEqualTo(Duration.ofSeconds(2));
            });
    }

    @Test void fakeRatesAndDelayBindFromProperties() {
        runner.withPropertyValues("pg.provider=fake", "pg.fake.approve-delay=15ms", "pg.fake.reject-rate=0.2", "pg.fake.unknown-rate=0.1")
            .run(context -> assertThat(context.getBean(FakePgProperties.class))
                .isEqualTo(new FakePgProperties(Duration.ofMillis(15), 0.2, 0.1)));
    }
}
