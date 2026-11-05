package com.malefashion.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        Outbox outbox,
        Messaging messaging,
        Mock mock
) {

    public record Outbox(
            long publishIntervalMs
    ) {
    }

    public record Messaging(
            String consumerName,
            Retry retry
    ) {
    }

    public record Retry(
            int maxAttempts,
            long initialIntervalMs,
            double multiplier,
            long maxIntervalMs
    ) {
    }

    public record Mock(
            boolean honorScenario,
            boolean silentOnTimeout
    ) {
    }
}
