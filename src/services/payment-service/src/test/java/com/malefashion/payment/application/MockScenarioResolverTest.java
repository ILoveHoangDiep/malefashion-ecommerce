package com.malefashion.payment.application;

import com.malefashion.payment.config.PaymentProperties;
import com.malefashion.payment.domain.model.PaymentScenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockScenarioResolverTest {

    @Test
    void ignoresRequestedScenarioWhenHonorScenarioIsOff() {
        MockScenarioResolver resolver = resolver(false, false);

        assertThat(resolver.resolve("FAILURE")).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(resolver.resolve("TIMEOUT")).isEqualTo(PaymentScenario.SUCCESS);
    }

    @Test
    void usesRequestedScenarioWhenHonorScenarioIsOn() {
        MockScenarioResolver resolver = resolver(true, false);

        assertThat(resolver.resolve("SUCCESS")).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(resolver.resolve("FAILURE")).isEqualTo(PaymentScenario.FAILURE);
        assertThat(resolver.resolve("TIMEOUT")).isEqualTo(PaymentScenario.TIMEOUT);
    }

    @Test
    void fallsBackToSuccessWhenScenarioIsMissing() {
        MockScenarioResolver resolver = resolver(true, false);

        assertThat(resolver.resolve(null)).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(resolver.resolve("")).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(resolver.resolve("   ")).isEqualTo(PaymentScenario.SUCCESS);
    }

    @Test
    void fallsBackToSuccessWhenScenarioIsUnknown() {
        MockScenarioResolver resolver = resolver(true, false);

        assertThat(resolver.resolve("EXPLODE")).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(resolver.resolve("success")).isEqualTo(PaymentScenario.SUCCESS);
    }

    @Test
    void trimsWhitespaceAroundKnownScenario() {
        MockScenarioResolver resolver = resolver(true, false);

        assertThat(resolver.resolve("  FAILURE ")).isEqualTo(PaymentScenario.FAILURE);
    }

    @Test
    void exposesSilentOnTimeoutFlag() {
        assertThat(resolver(true, true).isSilentOnTimeout()).isTrue();
        assertThat(resolver(true, false).isSilentOnTimeout()).isFalse();
    }

    private static MockScenarioResolver resolver(boolean honorScenario, boolean silentOnTimeout) {
        PaymentProperties properties = new PaymentProperties(
                null,
                null,
                new PaymentProperties.Mock(honorScenario, silentOnTimeout));
        return new MockScenarioResolver(properties);
    }
}
