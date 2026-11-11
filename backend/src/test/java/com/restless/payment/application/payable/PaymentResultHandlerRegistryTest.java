package com.restless.payment.application.payable;

import com.restless.payment.domain.PayableType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentResultHandlerRegistryTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PaymentResultHandlerRegistry.class);

    @Test void findsHandlerByType() {
        runner.withUserConfiguration(DropAndTicketHandlers.class).run(context -> {
            var registry = context.getBean(PaymentResultHandlerRegistry.class);
            assertThat(registry.get(PayableType.DROP).type()).isEqualTo(PayableType.DROP);
            assertThat(registry.get(PayableType.TICKET).type()).isEqualTo(PayableType.TICKET);
            assertThat(registry.supports(PayableType.FAN_MEETING)).isFalse();
        });
    }

    @Test void startsWithoutAnyHandlerButRefusesUnregisteredType() {
        runner.run(context -> {
            var registry = context.getBean(PaymentResultHandlerRegistry.class);
            assertThat(registry.supports(PayableType.DROP)).isFalse();
            org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> registry.get(PayableType.DROP)).withMessageContaining("DROP");
        });
    }

    @Test void duplicateHandlerForSameTypeFailsAtStartup() {
        runner.withUserConfiguration(DuplicateDropHandlers.class).run(context -> assertThat(context)
            .hasFailed().getFailure().rootCause().hasMessageContaining("Duplicate PaymentResultHandler for DROP"));
    }

    @Configuration(proxyBeanMethods = false)
    static class DropAndTicketHandlers {
        @Bean PaymentResultHandler drop() { return new StubHandler(PayableType.DROP); }
        @Bean PaymentResultHandler ticket() { return new StubHandler(PayableType.TICKET); }
    }

    @Configuration(proxyBeanMethods = false)
    static class DuplicateDropHandlers {
        @Bean PaymentResultHandler first() { return new StubHandler(PayableType.DROP); }
        @Bean PaymentResultHandler second() { return new StubHandler(PayableType.DROP); }
    }

    record StubHandler(PayableType type) implements PaymentResultHandler {
        @Override public Optional<PayableSnapshot> load(Long payableId) { return Optional.empty(); }
        @Override public ApprovalDecision onApprovalStarting(Long payableId) { return ApprovalDecision.accept(); }
        @Override public ApprovalDecision onApproved(Long payableId, ApprovedPayment payment) { return ApprovalDecision.accept(); }
        @Override public void onFailed(Long payableId) { }
        @Override public void onUnknown(Long payableId) { }
        @Override public void onRefunded(Long payableId) { }
    }
}
